package org.yurlib.server.library.application;

import java.time.Instant;
import java.util.UUID;

public record ConversionJob(
        UUID id,
        UUID sourceAssetId,
        UUID derivedAssetId,
        ConversionRoute route,
        State state,
        int attemptCount,
        boolean cancellationRequested,
        String errorCode,
        String safeDiagnostic,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        long version) {

    public enum State {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED_SAFE,
        CANCELLED
    }
}
