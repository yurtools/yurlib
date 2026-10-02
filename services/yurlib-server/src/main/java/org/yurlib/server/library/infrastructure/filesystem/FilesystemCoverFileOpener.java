package org.yurlib.server.library.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import org.yurlib.server.library.application.CoverContentFailure;
import org.yurlib.server.library.application.CoverContentLocation;
import org.yurlib.server.library.application.CoverFileOpener;
import org.yurlib.server.library.application.OpenedCoverContent;
import org.yurlib.server.library.domain.LibraryRoot;

public final class FilesystemCoverFileOpener implements CoverFileOpener {

    private final FilesystemRootVerifier verifier;

    public FilesystemCoverFileOpener(FilesystemRootVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public OpenedCoverContent open(LibraryRoot root, CoverContentLocation location) {
        if (root.mode() != LibraryRoot.Mode.MANAGED_OUTPUT) {
            throw unavailable();
        }
        var verifiedRoot = verifier.verifyConfigured(root).path();
        var candidate = verifiedRoot.resolve(location.normalizedRelativePath()).normalize();
        try {
            if (!candidate.startsWith(verifiedRoot) || Files.isSymbolicLink(candidate)) {
                throw unavailable();
            }
            var actual = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!actual.startsWith(verifiedRoot)
                    || !Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(actual) != location.byteSize()) {
                throw unavailable();
            }
            return new OpenedCoverContent(
                    Files.newInputStream(actual, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                    location.byteSize(),
                    location.mediaType(),
                    '"' + location.outputSha256() + '"');
        } catch (IOException failure) {
            throw new CoverContentFailure(
                    CoverContentFailure.Code.COVER_UNAVAILABLE, "The managed cover is unavailable.", failure);
        }
    }

    private static CoverContentFailure unavailable() {
        return new CoverContentFailure(CoverContentFailure.Code.COVER_UNAVAILABLE, "The managed cover is unavailable.");
    }
}
