package org.yurlib.server.library.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.yurlib.server.library.domain.Asset;

public interface CatalogQuery {

    CatalogPage search(String query, Set<Asset.Format> formats, int page, int size);

    default CatalogPage search(String query, int page, int size) {
        return search(query, Set.of(), page, size);
    }

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

    record AssetSummary(
            UUID id,
            Asset.Format format,
            long size,
            Availability availability,
            boolean original,
            CatalogReconciliation.MetadataState metadataState) {

        public AssetSummary(UUID id, Asset.Format format, long size, Availability availability, boolean original) {
            this(id, format, size, availability, original, CatalogReconciliation.MetadataState.READY);
        }
    }

    enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }
}
