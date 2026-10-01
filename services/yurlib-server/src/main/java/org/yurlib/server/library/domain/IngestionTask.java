package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;

public record IngestionTask(
        UUID id,
        UUID scanJobId,
        UUID libraryRootId,
        String idempotencyKey,
        String normalizedRelativePath,
        long byteSize,
        Instant modifiedAt,
        String fileKey,
        int memoryReservationMib,
        int attemptCount,
        UUID leaseToken,
        Instant leaseExpiresAt,
        boolean exhausted) {

    public IngestionTask {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(scanJobId, "scanJobId");
        DomainAssertions.required(libraryRootId, "libraryRootId");
        DomainAssertions.notBlank(idempotencyKey, "idempotencyKey");
        DomainAssertions.normalizedRelativePath(normalizedRelativePath, "normalizedRelativePath", false);
        DomainAssertions.nonNegative(byteSize, "byteSize");
        DomainAssertions.required(modifiedAt, "modifiedAt");
        DomainAssertions.nonNegative(attemptCount, "attemptCount");
        if (memoryReservationMib != 64 && memoryReservationMib != 128) {
            throw new IllegalArgumentException("memoryReservationMib must be 64 or 128");
        }
        if (!exhausted && (leaseToken == null || leaseExpiresAt == null)) {
            throw new IllegalArgumentException("A claimed ingestion task requires a lease.");
        }
    }
}
