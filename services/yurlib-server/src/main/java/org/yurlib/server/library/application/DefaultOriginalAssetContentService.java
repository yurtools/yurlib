package org.yurlib.server.library.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.yurlib.server.library.domain.AssetLocation;

public final class DefaultOriginalAssetContentService implements OriginalAssetContentUseCases {

    private final AssetContentStore assets;
    private final LibraryRootStore roots;
    private final AssetFileOpener opener;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The composition root owns these application ports for the service lifetime.")
    public DefaultOriginalAssetContentService(
            AssetContentStore assets, LibraryRootStore roots, AssetFileOpener opener) {
        this.assets = assets;
        this.roots = roots;
        this.opener = opener;
    }

    @Override
    public OpenedAssetContent open(UUID assetId) {
        var location = assets.findByAssetId(assetId)
                .orElseThrow(() -> new AssetContentFailure(
                        AssetContentFailure.Code.ASSET_NOT_FOUND, "The requested original asset does not exist."));
        if (location.availability() != AssetLocation.Availability.AVAILABLE) {
            throw new AssetContentFailure(
                    AssetContentFailure.Code.ASSET_UNAVAILABLE,
                    "The requested original asset is not currently available.");
        }
        var root = roots.findById(location.rootId())
                .orElseThrow(() -> new AssetContentFailure(
                        AssetContentFailure.Code.ASSET_UNAVAILABLE,
                        "The requested original asset is not currently available."));
        return opener.open(root, location);
    }
}
