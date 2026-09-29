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
        Instant observedAt) {}
