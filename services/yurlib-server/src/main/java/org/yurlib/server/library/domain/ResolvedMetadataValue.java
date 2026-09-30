package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResolvedMetadataValue(
        UUID id,
        MetadataObservation.SubjectType subjectType,
        UUID subjectId,
        String fieldName,
        MetadataValueState outcome,
        String resolvedValue,
        UUID selectedFactId,
        List<UUID> alternativeFactIds,
        String resolverName,
        String resolverVersion,
        long resolutionVersion,
        UUID supersedesValueId,
        boolean active,
        Instant createdAt) {

    public ResolvedMetadataValue {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(subjectType, "subjectType");
        DomainAssertions.required(subjectId, "subjectId");
        DomainAssertions.notBlank(fieldName, "fieldName");
        DomainAssertions.required(outcome, "outcome");
        alternativeFactIds = List.copyOf(DomainAssertions.required(alternativeFactIds, "alternativeFactIds"));
        DomainAssertions.notBlank(resolverName, "resolverName");
        DomainAssertions.notBlank(resolverVersion, "resolverVersion");
        DomainAssertions.required(createdAt, "createdAt");
        if (resolutionVersion <= 0) {
            throw new IllegalArgumentException("resolutionVersion must be positive");
        }
        if ((outcome == MetadataValueState.PRESENT) != (resolvedValue != null && selectedFactId != null)) {
            throw new IllegalArgumentException("resolved value and selected fact must match the outcome");
        }
    }
}
