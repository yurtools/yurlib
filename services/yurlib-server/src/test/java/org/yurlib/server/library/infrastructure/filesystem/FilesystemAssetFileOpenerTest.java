package org.yurlib.server.library.infrastructure.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yurlib.server.library.application.AssetContentFailure;
import org.yurlib.server.library.application.AssetContentLocation;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.domain.Asset;
import org.yurlib.server.library.domain.AssetLocation;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.infrastructure.config.LibraryStorageProperties;

class FilesystemAssetFileOpenerTest {

    private static final UUID ASSET_ID = UUID.fromString("9d889ec8-9ae5-49db-8d75-79d2969dd202");
    private static final UUID ROOT_ID = UUID.fromString("238fe729-8360-440e-94a7-012a23ac49f3");
    private static final String TOKEN = "private-token-1234";

    @TempDir
    private Path temporaryDirectory;

    private Path rootPath;
    private FilesystemRootVerifier rootVerifier;
    private FilesystemAssetFileOpener opener;
    private LibraryRoot root;

    @BeforeEach
    void prepareRoot() throws IOException {
        var mount = Files.createDirectory(temporaryDirectory.resolve("mount"));
        rootPath = Files.createDirectory(mount.resolve("books"));
        Files.writeString(rootPath.resolve(FilesystemRootVerifier.IDENTITY_MARKER), TOKEN, StandardCharsets.UTF_8);
        rootVerifier = new FilesystemRootVerifier(
                new MountAliasRegistry(List.of(new LibraryStorageProperties.Mount("main", mount))));
        var verified = rootVerifier.verify("main", "books", TOKEN);
        root = new LibraryRoot(
                ROOT_ID,
                "Main library",
                "main",
                verified.normalizedRelativePath(),
                verified.identityDigest(),
                LibraryRoot.Mode.READ_ONLY_SOURCE,
                LibraryRoot.Availability.AVAILABLE,
                null);
        opener = new FilesystemAssetFileOpener(rootVerifier);
    }

    @Test
    void revalidatesFactsAndOpensTheOriginalAsAStream() throws IOException {
        var file = Files.write(rootPath.resolve("book.epub"), new byte[] {1, 2, 3, 4});

        try (var opened = opener.open(root, location(file, "book.epub")).content()) {
            assertThat(opened.readAllBytes()).containsExactly(1, 2, 3, 4);
        }
    }

    @Test
    void rejectsAFileThatChangedAfterCataloging() throws IOException {
        var file = Files.write(rootPath.resolve("book.epub"), new byte[] {1, 2, 3});
        var cataloged = location(file, "book.epub");
        Files.write(file, new byte[] {1, 2, 3, 4});

        assertFailure(cataloged, AssetContentFailure.Code.FILE_UNSTABLE);
    }

    @Test
    void rejectsASymlinkInsteadOfFollowingIt() throws IOException {
        var outside = Files.write(temporaryDirectory.resolve("outside.epub"), new byte[] {1, 2, 3});
        var link = Files.createSymbolicLink(rootPath.resolve("link.epub"), outside);

        assertFailure(location(link, "link.epub"), AssetContentFailure.Code.PATH_ESCAPE);
    }

    @Test
    void rejectsAnIntermediateDirectorySymlinkInsteadOfFollowingIt() throws IOException {
        var outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        var outsideFile = Files.write(outside.resolve("book.epub"), new byte[] {1, 2, 3});
        Files.createSymbolicLink(rootPath.resolve("linked"), outside);

        assertFailure(location(outsideFile, "linked/book.epub"), AssetContentFailure.Code.PATH_ESCAPE);
    }

    @Test
    void refusesToOpenContentAfterTheRootIdentityChanges() throws IOException {
        var file = Files.write(rootPath.resolve("book.epub"), new byte[] {1, 2, 3});
        Files.writeString(
                rootPath.resolve(FilesystemRootVerifier.IDENTITY_MARKER), "different-token", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> opener.open(root, location(file, "book.epub")))
                .isInstanceOfSatisfying(
                        LibraryRootFailure.class,
                        failure ->
                                assertThat(failure.code()).isEqualTo(LibraryRootFailure.Code.ROOT_IDENTITY_MISMATCH));
    }

    private AssetContentLocation location(Path file, String relativePath) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new AssetContentLocation(
                ASSET_ID,
                ROOT_ID,
                relativePath,
                Asset.Format.EPUB,
                attributes.size(),
                attributes.lastModifiedTime().toInstant(),
                attributes.fileKey() == null ? null : attributes.fileKey().toString(),
                AssetLocation.Availability.AVAILABLE);
    }

    private void assertFailure(AssetContentLocation location, AssetContentFailure.Code code) {
        assertThatThrownBy(() -> opener.open(root, location))
                .isInstanceOfSatisfying(
                        AssetContentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
