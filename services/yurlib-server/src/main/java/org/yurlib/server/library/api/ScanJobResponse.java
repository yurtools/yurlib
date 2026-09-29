package org.yurlib.server.library.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.yurlib.server.library.application.ScanJobUseCases;
import org.yurlib.server.library.domain.ScanJob;

public record ScanJobResponse(
        UUID id,
        UUID rootId,
        ScanJob.State state,
        long discoveredCount,
        long processedCount,
        long skippedCount,
        long failedCount,
        boolean coverageComplete,
        List<FileFailureResponse> failures,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public ScanJobResponse {
        failures = List.copyOf(failures);
    }

    static ScanJobResponse queued(ScanJob job) {
        return from(job, List.of());
    }

    static ScanJobResponse from(ScanJobUseCases.ScanJobView view) {
        return from(
                view.job(),
                view.failures().stream().map(FileFailureResponse::from).toList());
    }

    private static ScanJobResponse from(ScanJob job, List<FileFailureResponse> failures) {
        return new ScanJobResponse(
                job.id(),
                job.libraryRootId(),
                job.state(),
                job.discoveredCount(),
                job.processedCount(),
                job.skippedCount(),
                job.failedCount(),
                job.completionCoverage(),
                failures,
                job.createdAt(),
                job.startedAt(),
                job.completedAt());
    }
}
