package org.yurlib.server.library.domain;

import java.util.UUID;

public record CatalogWork(UUID id, String provisionalTitle, ResolutionState resolutionState) {

    public CatalogWork {
        DomainAssertions.required(id, "id");
        DomainAssertions.notBlank(provisionalTitle, "provisionalTitle");
        DomainAssertions.required(resolutionState, "resolutionState");
    }

    public enum ResolutionState {
        PROVISIONAL,
        RESOLVED
    }
}
