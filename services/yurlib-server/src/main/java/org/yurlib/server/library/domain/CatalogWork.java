package org.yurlib.server.library.domain;

import java.util.UUID;

public record CatalogWork(
        UUID id, String provisionalTitle, ContentKind contentKind, ResolutionState resolutionState, long version) {

    public CatalogWork {
        DomainAssertions.required(id, "id");
        DomainAssertions.notBlank(provisionalTitle, "provisionalTitle");
        DomainAssertions.required(contentKind, "contentKind");
        DomainAssertions.required(resolutionState, "resolutionState");
        DomainAssertions.nonNegative(version, "version");
    }

    public enum ContentKind {
        BOOK,
        DOCUMENT,
        UNKNOWN
    }

    public enum ResolutionState {
        PROVISIONAL,
        RESOLVED
    }
}
