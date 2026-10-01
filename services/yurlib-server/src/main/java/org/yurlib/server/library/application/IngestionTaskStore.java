package org.yurlib.server.library.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.yurlib.server.library.domain.IngestionTask;

public interface IngestionTaskStore {

    boolean enqueue(
            UUID rootId,
            UUID scanJobId,
            String extractionVersion,
            ScanDiscovery.Candidate candidate,
            int queueCapacity,
            Instant createdAt);

    Optional<IngestionTask> claimNext(
            Instant claimedAt, Duration leaseDuration, int maximumAttempts, int fairRootShare);

    void heartbeat(UUID taskId, UUID leaseToken, Instant heartbeatAt, Instant leaseExpiresAt);

    void complete(UUID taskId, UUID leaseToken, Instant completedAt);

    void fail(UUID taskId, UUID leaseToken, String errorCode, boolean retryable, Instant failedAt);

    void cancelQueued(UUID scanJobId, Instant cancelledAt);

    long queuedCount();

    long runningCount();
}
