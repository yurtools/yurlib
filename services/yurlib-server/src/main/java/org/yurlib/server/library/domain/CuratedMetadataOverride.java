package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;

public record CuratedMetadataOverride(
        UUID id,
        MetadataObservation.SubjectType subjectType,
        UUID subjectId,
        String fieldName,
        MetadataValueState valueState,
        String curatedValue,
        UUID actorId,
        String reason,
        long overrideVersion,
        UUID supersedesOverrideId,
        UUID undoOfOverrideId,
        boolean active,
        Instant createdAt) {

    public CuratedMetadataOverride {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(subjectType, "subjectType");
        DomainAssertions.required(subjectId, "subjectId");
        DomainAssertions.notBlank(fieldName, "fieldName");
        DomainAssertions.required(valueState, "valueState");
        DomainAssertions.required(actorId, "actorId");
        DomainAssertions.notBlank(reason, "reason");
        DomainAssertions.required(createdAt, "createdAt");
        if (overrideVersion <= 0) {
            throw new IllegalArgumentException("overrideVersion must be positive");
        }
        if (valueState == MetadataValueState.CONFLICT) {
            throw new IllegalArgumentException("curated overrides cannot represent conflicts");
        }
        if ((valueState == MetadataValueState.PRESENT) != (curatedValue != null)) {
            throw new IllegalArgumentException("curated value must match its value state");
        }
    }
}
