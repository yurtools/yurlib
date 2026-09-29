package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;

public record FileOutcome(
        UUID scanJobId,
        String normalizedRelativePath,
        State state,
        String errorCode,
        String safeDiagnostic,
        int attemptCount,
        Instant updatedAt) {

    public FileOutcome {
        DomainAssertions.required(scanJobId, "scanJobId");
        DomainAssertions.normalizedRelativePath(normalizedRelativePath, "normalizedRelativePath", false);
        DomainAssertions.required(state, "state");
        DomainAssertions.positive(attemptCount, "attemptCount");
        DomainAssertions.required(updatedAt, "updatedAt");
    }

    public enum State {
        DISCOVERED,
        PROCESSED,
        SKIPPED,
        FAILED,
        DEFERRED
    }
}
