package org.yurlib.server.library.application;

import java.util.List;
import java.util.UUID;
import org.yurlib.server.library.domain.Asset;

public interface CatalogQuery {

    CatalogPage search(String query, int page, int size);

    record CatalogPage(List<WorkSummary> items, int page, int size, long totalElements) {
        public CatalogPage {
            items = List.copyOf(items);
        }
    }

    record WorkSummary(
            UUID id, String title, boolean provisional, List<String> contributors, List<AssetSummary> assets) {
        public WorkSummary {
            contributors = List.copyOf(contributors);
            assets = List.copyOf(assets);
        }
    }

    record AssetSummary(UUID id, Asset.Format format, long size, Availability availability, boolean original) {}

    enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }
}
