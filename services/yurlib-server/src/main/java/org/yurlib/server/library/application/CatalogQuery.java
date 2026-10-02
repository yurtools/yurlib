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
            UUID id,
            String title,
            boolean provisional,
            List<String> contributors,
            List<AssetSummary> assets,
            boolean coverAvailable,
            List<ContributorSummary> contributorDetails) {
        public WorkSummary {
            contributors = List.copyOf(contributors);
            assets = List.copyOf(assets);
            contributorDetails = List.copyOf(contributorDetails);
        }

        public WorkSummary(
                UUID id, String title, boolean provisional, List<String> contributors, List<AssetSummary> assets) {
            this(id, title, provisional, contributors, assets, false, List.of());
        }

        public WorkSummary(
                UUID id,
                String title,
                boolean provisional,
                List<String> contributors,
                List<AssetSummary> assets,
                boolean coverAvailable) {
            this(id, title, provisional, contributors, assets, coverAvailable, List.of());
        }
    }

    record ContributorSummary(UUID id, String displayName) {}

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
