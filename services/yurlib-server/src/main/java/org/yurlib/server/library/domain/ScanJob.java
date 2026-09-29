package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;

public record ScanJob(
        UUID id,
        UUID libraryRootId,
        State state,
        String correlationId,
        String extractionVersion,
        Instant createdAt,
        Instant startedAt,
        Instant heartbeatAt,
        Instant completedAt,
        long discoveredCount,
        long processedCount,
        long skippedCount,
        long failedCount,
        boolean completionCoverage,
        String errorSummary) {

    public ScanJob {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(libraryRootId, "libraryRootId");
        DomainAssertions.required(state, "state");
        DomainAssertions.notBlank(correlationId, "correlationId");
        DomainAssertions.notBlank(extractionVersion, "extractionVersion");
        DomainAssertions.required(createdAt, "createdAt");
        DomainAssertions.nonNegative(discoveredCount, "discoveredCount");
        DomainAssertions.nonNegative(processedCount, "processedCount");
        DomainAssertions.nonNegative(skippedCount, "skippedCount");
        DomainAssertions.nonNegative(failedCount, "failedCount");
        if (processedCount + skippedCount + failedCount > discoveredCount) {
            throw new IllegalArgumentException("completed counters cannot exceed discoveredCount");
        }
    }

    public enum State {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        COMPLETED_WITH_FAILURES,
        FAILED,
        CANCELLED
    }
}
