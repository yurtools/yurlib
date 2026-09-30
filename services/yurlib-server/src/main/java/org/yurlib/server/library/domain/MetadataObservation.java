package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;

public record MetadataObservation(
        UUID id,
        UUID observationSetId,
        UUID subjectId,
        SubjectType subjectType,
        String fieldName,
        String observedValue,
        ValueState valueState,
        int valueOrdinal,
        String absenceReason,
        UUID sourceAssetId,
        UUID sourceRootId,
        Source source,
        String parserName,
        String parserVersion,
        Instant observedAt) {

    public MetadataObservation {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(observationSetId, "observationSetId");
        DomainAssertions.required(subjectId, "subjectId");
        DomainAssertions.required(subjectType, "subjectType");
        DomainAssertions.notBlank(fieldName, "fieldName");
        DomainAssertions.required(valueState, "valueState");
        DomainAssertions.nonNegative(valueOrdinal, "valueOrdinal");
        DomainAssertions.required(sourceAssetId, "sourceAssetId");
        DomainAssertions.required(sourceRootId, "sourceRootId");
        DomainAssertions.required(source, "source");
        DomainAssertions.notBlank(parserName, "parserName");
        DomainAssertions.notBlank(parserVersion, "parserVersion");
        DomainAssertions.required(observedAt, "observedAt");
        if (valueState == ValueState.PRESENT && observedValue == null) {
            throw new IllegalArgumentException("present observations require a value");
        }
        if (valueState == ValueState.ABSENT
                && (observedValue != null || absenceReason == null || absenceReason.isBlank())) {
            throw new IllegalArgumentException("absent observations require a reason and no value");
        }
    }

    public enum SubjectType {
        WORK,
        EDITION,
        ASSET,
        CONTRIBUTOR
    }

    public enum Source {
        FILE
    }

    public enum ValueState {
        PRESENT,
        ABSENT
    }
}
