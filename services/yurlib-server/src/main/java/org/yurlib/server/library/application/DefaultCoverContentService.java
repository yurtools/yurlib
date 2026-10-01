package org.yurlib.server.library.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;

public final class DefaultCoverContentService implements CoverContentUseCases {

    private final CoverContentStore covers;
    private final LibraryRootStore roots;
    private final CoverFileOpener opener;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The composition root owns these application ports for the service lifetime.")
    public DefaultCoverContentService(CoverContentStore covers, LibraryRootStore roots, CoverFileOpener opener) {
        this.covers = covers;
        this.roots = roots;
        this.opener = opener;
    }

    @Override
    public OpenedCoverContent open(UUID workId) {
        var location = covers.findByWorkId(workId)
                .orElseThrow(() -> new CoverContentFailure(
                        CoverContentFailure.Code.COVER_NOT_FOUND, "No accessible cover is available for this work."));
        var root = roots.findById(location.rootId())
                .orElseThrow(() -> new CoverContentFailure(
                        CoverContentFailure.Code.COVER_UNAVAILABLE, "The managed cover is unavailable."));
        return opener.open(root, location);
    }
}
