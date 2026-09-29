package org.yurlib.server.library.infrastructure.filesystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.application.RootLocationVerifier;
import org.yurlib.server.library.domain.LibraryRoot;

public final class FilesystemRootVerifier implements RootLocationVerifier {

    static final String IDENTITY_MARKER = ".yurlib-root-id";
    private static final long MAXIMUM_MARKER_BYTES = 1024;

    private final MountAliasRegistry registry;

    public FilesystemRootVerifier(MountAliasRegistry registry) {
        this.registry = registry;
    }

    @Override
    public VerifiedRootLocation verify(String mountAlias, String relativePath, String identityToken) {
        var resolved = registry.resolve(mountAlias, relativePath);
        var marker = resolved.path().resolve(IDENTITY_MARKER);
        try {
            var markerTarget = marker.toRealPath();
            if (!markerTarget.startsWith(resolved.path())) {
                throw pathEscape();
            }
            if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) > MAXIMUM_MARKER_BYTES) {
                throw unavailable();
            }
            var observedToken = Files.readString(marker, StandardCharsets.UTF_8).strip();
            if (!MessageDigest.isEqual(
                    observedToken.getBytes(StandardCharsets.UTF_8), identityToken.getBytes(StandardCharsets.UTF_8))) {
                throw new LibraryRootFailure(
                        LibraryRootFailure.Code.ROOT_IDENTITY_MISMATCH,
                        "The library root identity marker does not match.");
            }
            return new VerifiedRootLocation(resolved.normalizedRelativePath(), sha256(identityToken));
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    MountAliasRegistry.ResolvedRoot verifyConfigured(LibraryRoot root) {
        var resolved = registry.resolve(root.mountAlias(), root.relativeBasePath());
        var marker = resolved.path().resolve(IDENTITY_MARKER);
        try {
            var markerTarget = marker.toRealPath();
            if (!markerTarget.startsWith(resolved.path())) {
                throw pathEscape();
            }
            if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) > MAXIMUM_MARKER_BYTES) {
                throw unavailable();
            }
            var observedToken = Files.readString(marker, StandardCharsets.UTF_8).strip();
            var observedDigest = HexFormat.of().parseHex(sha256(observedToken));
            var expectedDigest = HexFormat.of().parseHex(root.expectedIdentityDigest());
            if (!MessageDigest.isEqual(observedDigest, expectedDigest)) {
                throw new LibraryRootFailure(
                        LibraryRootFailure.Code.ROOT_IDENTITY_MISMATCH,
                        "The library root identity marker does not match.");
            }
            return resolved;
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static LibraryRootFailure unavailable() {
        return new LibraryRootFailure(
                LibraryRootFailure.Code.ROOT_UNAVAILABLE, "The configured library root is unavailable.");
    }

    private static LibraryRootFailure unavailable(IOException cause) {
        return new LibraryRootFailure(
                LibraryRootFailure.Code.ROOT_UNAVAILABLE, "The configured library root is unavailable.", cause);
    }

    private static LibraryRootFailure pathEscape() {
        return new LibraryRootFailure(
                LibraryRootFailure.Code.PATH_ESCAPE, "The library root identity marker escapes its allowed root.");
    }
}
