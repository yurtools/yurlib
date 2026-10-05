package org.yurlib.server.library.api;

import java.util.UUID;
import org.yurlib.server.library.application.CatalogQuery;

public record CatalogAssetResponse(
        UUID id,
        UUID editionId,
        String format,
        long size,
        String availability,
        boolean original,
        String metadataState) {

    static CatalogAssetResponse from(CatalogQuery.AssetSummary asset) {
        return new CatalogAssetResponse(
                asset.id(),
                asset.editionId(),
                asset.format().name(),
                asset.size(),
                asset.availability().name(),
                asset.original(),
                asset.metadataState().name());
    }
}
