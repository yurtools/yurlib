package org.yurlib.server.library.domain;

import java.util.UUID;

public record Contributor(UUID id, String displayName, Kind kind, long version) {

    public Contributor {
        DomainAssertions.required(id, "id");
        DomainAssertions.notBlank(displayName, "displayName");
        DomainAssertions.required(kind, "kind");
        DomainAssertions.nonNegative(version, "version");
    }

    public enum Kind {
        PERSON,
        ORGANIZATION,
        UNRESOLVED
    }
}
