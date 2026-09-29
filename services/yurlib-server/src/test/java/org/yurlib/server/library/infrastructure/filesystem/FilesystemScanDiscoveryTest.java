package org.yurlib.server.library.infrastructure.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.infrastructure.config.LibraryStorageProperties;

class FilesystemScanDiscoveryTest {

    private static final String TOKEN = "private-token-1234";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void discoversSupportedFilesWithoutFollowingSymbolicLinks() throws IOException {
        var mount = Files.createDirectory(temporaryDirectory.resolve("mount"));
        var rootPath = Files.createDirectories(mount.resolve("books/fiction"));
        Files.writeString(mount.resolve("books/.yurlib-root-id"), TOKEN, StandardCharsets.UTF_8);
        Files.writeString(rootPath.resolve("one.EPUB"), "content", StandardCharsets.UTF_8);
        Files.writeString(rootPath.resolve("two.fb2"), "content", StandardCharsets.UTF_8);
        Files.writeString(rootPath.resolve("notes.txt"), "ignored", StandardCharsets.UTF_8);
        var outside = Files.writeString(temporaryDirectory.resolve("outside.mobi"), "private", StandardCharsets.UTF_8);
        Files.createSymbolicLink(rootPath.resolve("escape.mobi"), outside);
        var listener = new RecordingListener();

        var result = discovery(mount).discover(root(), listener);

        assertThat(result.coverageComplete()).isTrue();
        assertThat(listener.discovered)
                .extracting(ScanDiscovery.Candidate::normalizedRelativePath)
                .containsExactlyInAnyOrder("fiction/one.EPUB", "fiction/two.fb2");
        assertThat(listener.failures).containsExactly("fiction/escape.mobi:PATH_ESCAPE");
        assertThat(listener.heartbeats).isGreaterThanOrEqualTo(5);
    }

    private FilesystemScanDiscovery discovery(Path mount) {
        var registry = new MountAliasRegistry(List.of(new LibraryStorageProperties.Mount("main", mount)));
        return new FilesystemScanDiscovery(new FilesystemRootVerifier(registry));
    }

    private static LibraryRoot root() {
        return new LibraryRoot(
                UUID.randomUUID(),
                "Main library",
                "main",
                "books",
                sha256(TOKEN),
                LibraryRoot.Mode.READ_ONLY,
                LibraryRoot.Availability.AVAILABLE,
                null);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class RecordingListener implements ScanDiscovery.Listener {

        private final List<ScanDiscovery.Candidate> discovered = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private int heartbeats;

        @Override
        public void heartbeat() {
            heartbeats++;
        }

        @Override
        public void discovered(ScanDiscovery.Candidate candidate) {
            discovered.add(candidate);
        }

        @Override
        public void failed(String normalizedRelativePath, String code, String safeDiagnostic) {
            failures.add(normalizedRelativePath + ":" + code);
        }
    }
}
