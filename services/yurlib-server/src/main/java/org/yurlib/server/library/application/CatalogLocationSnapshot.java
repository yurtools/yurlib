package org.yurlib.server.library.application;

import java.time.Instant;

public record CatalogLocationSnapshot(
        long byteSize,
        Instant modifiedAt,
        String extractionVersion,
        CatalogReconciliation.MetadataState metadataState) {

    public CatalogLocationSnapshot(long byteSize, Instant modifiedAt, String extractionVersion) {
        this(byteSize, modifiedAt, extractionVersion, CatalogReconciliation.MetadataState.READY);
    }
}
