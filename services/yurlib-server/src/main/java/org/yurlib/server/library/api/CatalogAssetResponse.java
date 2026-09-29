package org.yurlib.server.library.api;

import java.util.UUID;
import org.yurlib.server.library.application.CatalogQuery;

public record CatalogAssetResponse(UUID id, String format, long size, String availability, boolean original) {

    static CatalogAssetResponse from(CatalogQuery.AssetSummary asset) {
        return new CatalogAssetResponse(
                asset.id(),
                asset.format().name(),
                asset.size(),
                asset.availability().name(),
                asset.original());
    }
}
