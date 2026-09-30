package org.yurlib.server.library.domain;

import java.util.Map;
import java.util.UUID;

public record Edition(
        UUID id,
        UUID workId,
        String observedLanguage,
        Map<String, String> identifiers,
        ResolutionState resolutionState,
        long version) {

    public Edition {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(workId, "workId");
        identifiers = Map.copyOf(DomainAssertions.required(identifiers, "identifiers"));
        DomainAssertions.required(resolutionState, "resolutionState");
        DomainAssertions.nonNegative(version, "version");
    }

    public enum ResolutionState {
        PROVISIONAL,
        RESOLVED
    }
}
