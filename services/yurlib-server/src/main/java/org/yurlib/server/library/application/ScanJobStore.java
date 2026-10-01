package org.yurlib.server.library.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.ScanJob;

public interface ScanJobStore {

    ScanJob queue(UUID rootId, String correlationId, String extractionVersion, Instant createdAt);

    Optional<ScanJob> findById(UUID jobId);

    List<FileOutcome> findFailures(UUID jobId, int limit);

    Optional<ScanJob> claimNext(Instant claimedAt, Instant leaseExpiredBefore);

    void finishDiscovery(UUID jobId, boolean coverageComplete, Instant completedAt);

    boolean isCancellationRequested(UUID jobId);

    ScanJob cancel(UUID jobId, Instant cancelledAt);

    Optional<ScanJob> completeIfReady(UUID jobId, Instant completedAt);

    long queuedCount();

    void recordOutcome(FileOutcome outcome);

    void heartbeat(UUID jobId, Instant heartbeatAt);

    ScanJob complete(UUID jobId, boolean coverageComplete, Instant completedAt);

    ScanJob fail(UUID jobId, String safeSummary, Instant completedAt);
}
