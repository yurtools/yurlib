package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;

public record NormalizedMetadataFact(
        UUID id,
        UUID sourceObservationId,
        MetadataObservation.SubjectType subjectType,
        UUID subjectId,
        String fieldName,
        MetadataValueState valueState,
        String normalizedValue,
        String valueType,
        String normalizerName,
        String normalizerVersion,
        Confidence confidence,
        UUID supersedesFactId,
        Instant createdAt) {

    public NormalizedMetadataFact {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(sourceObservationId, "sourceObservationId");
        DomainAssertions.required(subjectType, "subjectType");
        DomainAssertions.required(subjectId, "subjectId");
        DomainAssertions.notBlank(fieldName, "fieldName");
        DomainAssertions.required(valueState, "valueState");
        DomainAssertions.notBlank(valueType, "valueType");
        DomainAssertions.notBlank(normalizerName, "normalizerName");
        DomainAssertions.notBlank(normalizerVersion, "normalizerVersion");
        DomainAssertions.required(confidence, "confidence");
        DomainAssertions.required(createdAt, "createdAt");
        if (valueState == MetadataValueState.CONFLICT) {
            throw new IllegalArgumentException("normalized facts cannot represent conflicts");
        }
        if ((valueState == MetadataValueState.PRESENT) != (normalizedValue != null)) {
            throw new IllegalArgumentException("normalized value must match its value state");
        }
    }

    public enum Confidence {
        EXACT,
        HIGH,
        MEDIUM,
        LOW,
        UNKNOWN
    }
}
