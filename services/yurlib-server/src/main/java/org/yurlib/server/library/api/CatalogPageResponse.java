package org.yurlib.server.library.api;

import java.util.List;
import org.yurlib.server.library.application.CatalogQuery;

public record CatalogPageResponse(List<CatalogWorkResponse> items, int page, int size, long totalElements) {

    public CatalogPageResponse {
        items = List.copyOf(items);
    }

    static CatalogPageResponse from(CatalogQuery.CatalogPage page) {
        return new CatalogPageResponse(
                page.items().stream().map(CatalogWorkResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements());
    }
}
