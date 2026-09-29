package org.yurlib.server.library.api;

import java.util.List;
import java.util.UUID;
import org.yurlib.server.library.application.CatalogQuery;

public record CatalogWorkResponse(
        UUID id, String title, List<String> contributors, boolean provisional, List<CatalogAssetResponse> assets) {

    public CatalogWorkResponse {
        contributors = List.copyOf(contributors);
        assets = List.copyOf(assets);
    }

    static CatalogWorkResponse from(CatalogQuery.WorkSummary work) {
        return new CatalogWorkResponse(
                work.id(),
                work.title(),
                work.contributors(),
                work.provisional(),
                work.assets().stream().map(CatalogAssetResponse::from).toList());
    }
}
