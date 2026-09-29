package org.yurlib.server.library.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.domain.LibraryRoot;

public final class FilesystemScanDiscovery implements ScanDiscovery {

    private final FilesystemRootVerifier rootVerifier;

    public FilesystemScanDiscovery(FilesystemRootVerifier rootVerifier) {
        this.rootVerifier = rootVerifier;
    }

    @Override
    public DiscoveryResult discover(LibraryRoot root, Listener listener) {
        var verified = rootVerifier.verifyConfigured(root);
        var visitor = new DiscoveryVisitor(verified.path(), listener);
        try {
            Files.walkFileTree(verified.path(), visitor);
            return new DiscoveryResult(visitor.coverageComplete);
        } catch (IOException failure) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_UNAVAILABLE,
                    "The configured library root could not be scanned completely.",
                    failure);
        }
    }

    private static final class DiscoveryVisitor extends SimpleFileVisitor<Path> {

        private final Path root;
        private final Listener listener;
        private boolean coverageComplete = true;

        private DiscoveryVisitor(Path root, Listener listener) {
            this.root = root;
            this.listener = listener;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            listener.heartbeat();
            if (!directory.equals(root) && attributes.isSymbolicLink()) {
                listener.failed(relative(directory), "PATH_ESCAPE", "A symbolic link was not followed.");
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            listener.heartbeat();
            var relativePath = relative(file);
            if (attributes.isSymbolicLink()) {
                listener.failed(relativePath, "PATH_ESCAPE", "A symbolic link was not followed.");
                return FileVisitResult.CONTINUE;
            }
            if (!attributes.isRegularFile() || !isSupportedCandidate(relativePath)) {
                return FileVisitResult.CONTINUE;
            }
            var actual = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!actual.startsWith(root)) {
                listener.failed(relativePath, "PATH_ESCAPE", "A candidate escaped the configured root.");
                return FileVisitResult.CONTINUE;
            }
            listener.discovered(new Candidate(
                    relativePath,
                    actual,
                    attributes.size(),
                    attributes.lastModifiedTime().toInstant(),
                    attributes.fileKey() == null ? null : attributes.fileKey().toString()));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            listener.heartbeat();
            if (file.equals(root)) {
                throw failure;
            }
            coverageComplete = false;
            listener.failed(relative(file), "FILE_UNREADABLE", "A library entry could not be inspected.");
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
            if (failure == null) {
                return FileVisitResult.CONTINUE;
            }
            if (directory.equals(root)) {
                throw failure;
            }
            coverageComplete = false;
            listener.failed(relative(directory), "FILE_UNREADABLE", "A library directory could not be read.");
            return FileVisitResult.CONTINUE;
        }

        private String relative(Path path) {
            return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        }

        private static boolean isSupportedCandidate(String path) {
            var lowercase = path.toLowerCase(Locale.ROOT);
            return lowercase.endsWith(".epub") || lowercase.endsWith(".fb2") || lowercase.endsWith(".mobi");
        }
    }
}
