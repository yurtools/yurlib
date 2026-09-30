package org.yurlib.server.library.application;

import java.util.Optional;
import java.util.UUID;

public interface CatalogStore {

    Optional<CatalogLocationSnapshot> findLocation(UUID rootId, String normalizedRelativePath);

    void markSeen(UUID rootId, UUID scanJobId, String normalizedRelativePath);

    void reconcile(CatalogReconciliation reconciliation);

    void markMetadataState(
            UUID rootId, String normalizedRelativePath, CatalogReconciliation.MetadataState metadataState);

    void markUnseenMissing(UUID rootId, UUID scanJobId);
}
