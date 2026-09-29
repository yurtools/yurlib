package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;

public record MetadataObservation(
        UUID id,
        UUID subjectId,
        SubjectType subjectType,
        String fieldName,
        String observedValue,
        Source source,
        String parserName,
        String parserVersion,
        Instant observedAt) {

    public MetadataObservation {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(subjectId, "subjectId");
        DomainAssertions.required(subjectType, "subjectType");
        DomainAssertions.notBlank(fieldName, "fieldName");
        DomainAssertions.required(observedValue, "observedValue");
        DomainAssertions.required(source, "source");
        DomainAssertions.notBlank(parserName, "parserName");
        DomainAssertions.notBlank(parserVersion, "parserVersion");
        DomainAssertions.required(observedAt, "observedAt");
        if (source != Source.FILE) {
            throw new IllegalArgumentException("the first slice supports file observations only");
        }
    }

    public enum SubjectType {
        WORK,
        EDITION,
        ASSET
    }

    public enum Source {
        FILE
    }
}
