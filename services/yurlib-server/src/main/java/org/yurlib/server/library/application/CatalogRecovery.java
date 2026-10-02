package org.yurlib.server.library.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CatalogRecovery {

    RecoveryPreview preview(SubjectType subjectType, UUID survivorId, UUID sourceId);

    MergeOperation merge(
            SubjectType subjectType,
            UUID survivorId,
            UUID sourceId,
            long survivorVersion,
            long sourceVersion,
            UUID idempotencyKey,
            String reason,
            UUID actorId);

    SplitPreview splitPreview(UUID operationId);

    MergeOperation undo(UUID operationId, String reason, UUID actorId);

    DuplicateDecision markNotSame(
            SubjectType subjectType,
            UUID firstId,
            UUID secondId,
            String ruleName,
            String ruleVersion,
            String reason,
            UUID actorId);

    List<MergeOperation> history(SubjectType subjectType, UUID subjectId);

    enum SubjectType {
        WORK,
        EDITION,
        CONTRIBUTOR
    }

    record SubjectSummary(UUID id, String displayName, long version) {}

    record Impact(
            long editions,
            long assets,
            long observations,
            long contributors,
            long tags,
            long personalReadStates,
            long collectionMemberships,
            long favoriteUsers) {}

    record RecoveryPreview(
            SubjectType subjectType,
            SubjectSummary survivor,
            SubjectSummary source,
            Impact impact,
            boolean mergeAllowed,
            List<String> conflicts) {
        public RecoveryPreview {
            conflicts = List.copyOf(conflicts);
        }
    }

    record MergeOperation(
            UUID id,
            SubjectType subjectType,
            UUID survivorId,
            UUID sourceId,
            String status,
            UUID actorId,
            String reason,
            Instant createdAt,
            Instant undoneAt) {}

    record SplitPreview(MergeOperation operation, boolean automaticUndoAllowed, List<String> conflicts) {
        public SplitPreview {
            conflicts = List.copyOf(conflicts);
        }
    }

    record DuplicateDecision(
            UUID id,
            SubjectType subjectType,
            UUID firstSubjectId,
            UUID secondSubjectId,
            String ruleName,
            String ruleVersion,
            String reason,
            Instant createdAt) {}
}
