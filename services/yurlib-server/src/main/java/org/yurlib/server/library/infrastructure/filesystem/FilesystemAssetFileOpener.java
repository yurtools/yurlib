package org.yurlib.server.library.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.temporal.ChronoUnit;
import org.yurlib.server.library.application.AssetContentFailure;
import org.yurlib.server.library.application.AssetContentLocation;
import org.yurlib.server.library.application.AssetFileOpener;
import org.yurlib.server.library.application.OpenedAssetContent;
import org.yurlib.server.library.domain.LibraryRoot;

public final class FilesystemAssetFileOpener implements AssetFileOpener {

    private final FilesystemRootVerifier rootVerifier;

    public FilesystemAssetFileOpener(FilesystemRootVerifier rootVerifier) {
        this.rootVerifier = rootVerifier;
    }

    @Override
    public OpenedAssetContent open(LibraryRoot root, AssetContentLocation location) {
        var verifiedRoot = rootVerifier.verifyConfigured(root).path();
        var candidate = verifiedRoot.resolve(location.normalizedRelativePath()).normalize();
        if (!candidate.startsWith(verifiedRoot)) {
            throw pathEscape();
        }
        try {
            rejectSymbolicLinks(verifiedRoot, candidate);
            var actual = candidate.toRealPath();
            if (!actual.startsWith(verifiedRoot)) {
                throw pathEscape();
            }
            var attributes = Files.readAttributes(actual, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw unavailable();
            }
            var currentFileKey =
                    attributes.fileKey() == null ? null : attributes.fileKey().toString();
            if (attributes.size() != location.byteSize()
                    || !samePersistedTimestamp(attributes, location)
                    || fileKeyChanged(location.fileKey(), currentFileKey)) {
                throw unstable();
            }
            return new OpenedAssetContent(
                    location.assetId(),
                    location.format(),
                    location.byteSize(),
                    filename(location.normalizedRelativePath()),
                    Files.newInputStream(actual, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException failure) {
            throw new AssetContentFailure(
                    AssetContentFailure.Code.ASSET_UNAVAILABLE,
                    "The requested original asset is not currently available.",
                    failure);
        }
    }

    private static void rejectSymbolicLinks(Path root, Path candidate) {
        var component = root;
        for (var name : root.relativize(candidate)) {
            component = component.resolve(name);
            if (Files.isSymbolicLink(component)) {
                throw pathEscape();
            }
        }
    }

    private static boolean samePersistedTimestamp(BasicFileAttributes attributes, AssetContentLocation location) {
        return attributes
                .lastModifiedTime()
                .toInstant()
                .truncatedTo(ChronoUnit.MICROS)
                .equals(location.modifiedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private static boolean fileKeyChanged(String stored, String current) {
        return stored != null && !stored.equals(current);
    }

    private static String filename(String normalizedRelativePath) {
        var separator = normalizedRelativePath.lastIndexOf('/');
        return separator < 0 ? normalizedRelativePath : normalizedRelativePath.substring(separator + 1);
    }

    private static AssetContentFailure pathEscape() {
        return new AssetContentFailure(
                AssetContentFailure.Code.PATH_ESCAPE, "The requested original asset failed containment validation.");
    }

    private static AssetContentFailure unavailable() {
        return new AssetContentFailure(
                AssetContentFailure.Code.ASSET_UNAVAILABLE, "The requested original asset is not currently available.");
    }

    private static AssetContentFailure unstable() {
        return new AssetContentFailure(
                AssetContentFailure.Code.FILE_UNSTABLE, "The requested original asset changed after it was cataloged.");
    }
}
