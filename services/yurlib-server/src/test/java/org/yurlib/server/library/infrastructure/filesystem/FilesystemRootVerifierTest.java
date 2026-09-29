package org.yurlib.server.library.infrastructure.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.infrastructure.config.LibraryStorageProperties;

class FilesystemRootVerifierTest {

    private static final String TOKEN = "private-token-1234";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void verifiesUnicodePathsWithoutChangingTheOperatorMarker() throws IOException {
        var mount = Files.createDirectory(temporaryDirectory.resolve("mount"));
        var root = Files.createDirectories(mount.resolve("Bücher/日本語"));
        var marker = root.resolve(FilesystemRootVerifier.IDENTITY_MARKER);
        Files.writeString(marker, TOKEN + System.lineSeparator(), StandardCharsets.UTF_8);
        var bytesBefore = Files.readAllBytes(marker);
        var modifiedBefore = Files.getLastModifiedTime(marker);

        var verified = verifier("main", mount).verify("main", "Bücher/日本語", TOKEN);

        assertThat(verified.normalizedRelativePath()).isEqualTo("Bücher/日本語");
        assertThat(verified.identityDigest()).matches("[0-9a-f]{64}").doesNotContain(TOKEN);
        assertThat(Files.readAllBytes(marker)).isEqualTo(bytesBefore);
        assertThat(Files.getLastModifiedTime(marker)).isEqualTo(modifiedBefore);
    }

    @Test
    void reportsMissingAndMismatchedMarkersDistinctly() throws IOException {
        var mount = Files.createDirectory(temporaryDirectory.resolve("mount"));
        var missing = Files.createDirectory(mount.resolve("missing"));
        var mismatch = Files.createDirectory(mount.resolve("mismatch"));
        Files.writeString(
                mismatch.resolve(FilesystemRootVerifier.IDENTITY_MARKER),
                "a-different-private-token",
                StandardCharsets.UTF_8);
        var verifier = verifier("main", mount);

        assertFailure(verifier, "missing", TOKEN, LibraryRootFailure.Code.ROOT_UNAVAILABLE);
        assertFailure(verifier, "mismatch", TOKEN, LibraryRootFailure.Code.ROOT_IDENTITY_MISMATCH);
        try (var contents = Files.list(missing)) {
            assertThat(contents).isEmpty();
        }
    }

    @Test
    void rejectsTraversalAbsoluteBackslashAndEscapingSymlinkPaths() throws IOException {
        var mount = Files.createDirectory(temporaryDirectory.resolve("mount"));
        var outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.createSymbolicLink(mount.resolve("escape"), outside);
        var verifier = verifier("main", mount);

        assertFailure(verifier, "../outside", TOKEN, LibraryRootFailure.Code.PATH_ESCAPE);
        assertFailure(verifier, outside.toString(), TOKEN, LibraryRootFailure.Code.PATH_ESCAPE);
        assertFailure(verifier, "books\\outside", TOKEN, LibraryRootFailure.Code.PATH_ESCAPE);
        assertFailure(verifier, "escape", TOKEN, LibraryRootFailure.Code.PATH_ESCAPE);
    }

    @Test
    void treatsAliasesAsCaseSensitive() throws IOException {
        var mount = Files.createDirectory(temporaryDirectory.resolve("mount"));
        var verifier = verifier("main", mount);

        assertFailure(verifier, "MAIN", "", TOKEN, LibraryRootFailure.Code.ROOT_NOT_ALLOWED);
    }

    @Test
    void rejectsOverlappingCanonicalMounts() throws IOException {
        var parent = Files.createDirectory(temporaryDirectory.resolve("parent"));
        var child = Files.createDirectory(parent.resolve("child"));
        var mounts = List.of(mount("parent", parent), mount("child", child));

        assertThatThrownBy(() -> new MountAliasRegistry(mounts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not overlap")
                .hasMessageNotContaining(parent.toString());
    }

    private static FilesystemRootVerifier verifier(String alias, Path path) {
        return new FilesystemRootVerifier(new MountAliasRegistry(List.of(mount(alias, path))));
    }

    private static LibraryStorageProperties.Mount mount(String alias, Path path) {
        return new LibraryStorageProperties.Mount(alias, path);
    }

    private static void assertFailure(
            FilesystemRootVerifier verifier, String relativePath, String identityToken, LibraryRootFailure.Code code) {
        assertFailure(verifier, "main", relativePath, identityToken, code);
    }

    private static void assertFailure(
            FilesystemRootVerifier verifier,
            String alias,
            String relativePath,
            String identityToken,
            LibraryRootFailure.Code code) {
        assertThatThrownBy(() -> verifier.verify(alias, relativePath, identityToken))
                .isInstanceOfSatisfying(
                        LibraryRootFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
