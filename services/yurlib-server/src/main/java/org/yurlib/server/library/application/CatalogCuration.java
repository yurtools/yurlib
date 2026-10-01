package org.yurlib.server.library.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CatalogCuration {

    WorkCuration findWork(UUID workId);

    WorkCuration updateTitle(UUID workId, String value, String reason, long expectedVersion, UUID actorId);

    WorkCuration undoTitle(UUID workId, String reason, long expectedVersion, UUID actorId);

    WorkCuration replaceTags(UUID workId, List<String> tags, String reason, long expectedVersion, UUID actorId);

    ContributorCuration updateContributor(
            UUID contributorId,
            String displayName,
            List<String> aliases,
            String reason,
            long expectedVersion,
            UUID actorId);

    List<ReviewItem> findOpenReviews(int limit);

    void dismissReview(UUID reviewId, String reason, UUID actorId);

    record WorkCuration(
            UUID id,
            long version,
            MetadataField title,
            List<ContributorCuration> contributors,
            List<String> tags,
            List<ReviewItem> reviews,
            List<AuditEvent> audit) {
        public WorkCuration {
            contributors = List.copyOf(contributors);
            tags = List.copyOf(tags);
            reviews = List.copyOf(reviews);
            audit = List.copyOf(audit);
        }
    }

    record ContributorCuration(UUID id, String displayName, String role, long version, List<String> aliases) {
        public ContributorCuration {
            aliases = List.copyOf(aliases);
        }
    }

    record MetadataField(
            String value,
            String source,
            long overrideVersion,
            List<String> observedValues,
            List<OverrideHistory> history) {
        public MetadataField {
            observedValues = List.copyOf(observedValues);
            history = List.copyOf(history);
        }
    }

    record OverrideHistory(
            UUID id,
            String value,
            String valueState,
            UUID actorId,
            String reason,
            long version,
            boolean active,
            Instant createdAt) {}

    record ReviewItem(
            UUID id,
            UUID subjectId,
            String subjectType,
            String fieldName,
            String reasonCode,
            String detail,
            String ruleName,
            String ruleVersion,
            Instant createdAt) {}

    record AuditEvent(UUID id, String eventType, UUID actorId, String reason, Instant createdAt) {}
}
