package org.yurlib.server.library.application;

import java.time.Instant;
import java.util.UUID;

public record CatalogReconciliation(
        UUID rootId,
        UUID scanJobId,
        String normalizedRelativePath,
        String fileKey,
        String extractionVersion,
        ExtractedBookMetadata metadata,
        Instant observedAt,
        MetadataState metadataState) {

    public CatalogReconciliation(
            UUID rootId,
            UUID scanJobId,
            String normalizedRelativePath,
            String fileKey,
            String extractionVersion,
            ExtractedBookMetadata metadata,
            Instant observedAt) {
        this(
                rootId,
                scanJobId,
                normalizedRelativePath,
                fileKey,
                extractionVersion,
                metadata,
                observedAt,
                MetadataState.READY);
    }

    public enum MetadataState {
        PENDING,
        READY,
        FAILED_SAFE
    }
}
