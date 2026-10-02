package org.yurlib.server.library.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.CatalogRecovery;
import org.yurlib.server.library.application.CatalogRecoveryFailure;
import org.yurlib.server.library.application.CatalogRecoveryFailureInjector;
import org.yurlib.server.library.application.LibraryAccessContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcCatalogRecovery implements CatalogRecovery {

    private static final int MAXIMUM_HISTORY = 100;

    private final JdbcClient jdbc;
    private final LibraryAccessContext accessContext;
    private final CatalogRecoveryFailureInjector failureInjector;
    private final ObjectMapper objectMapper;

    public JdbcCatalogRecovery(
            JdbcClient jdbc,
            LibraryAccessContext accessContext,
            CatalogRecoveryFailureInjector failureInjector,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.accessContext = accessContext;
        this.failureInjector = failureInjector;
        this.objectMapper = objectMapper.rebuild().build();
    }

    @Override
    public RecoveryPreview preview(SubjectType subjectType, UUID survivorId, UUID sourceId) {
        requireDifferent(survivorId, sourceId);
        var survivor = requireSubject(subjectType, survivorId, false);
        var source = requireSubject(subjectType, sourceId, false);
        var conflicts = conflicts(subjectType, survivorId, sourceId);
        return new RecoveryPreview(
                subjectType, survivor, source, impact(subjectType, sourceId), conflicts.isEmpty(), conflicts);
    }

    @Override
    @Transactional
    public MergeOperation merge(
            SubjectType subjectType,
            UUID survivorId,
            UUID sourceId,
            long survivorVersion,
            long sourceVersion,
            UUID idempotencyKey,
            String reason,
            UUID actorId) {
        var normalizedReason = requireText(reason, "reason", 1_000);
        var existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            if (existing.subjectType() != subjectType
                    || !existing.survivorId().equals(survivorId)
                    || !existing.sourceId().equals(sourceId)) {
                throw failure(CatalogRecoveryFailure.Code.INVALID_MERGE, "The idempotency key is already in use.");
            }
            return existing;
        }
        requireDifferent(survivorId, sourceId);
        var survivor = requireSubject(subjectType, survivorId, true);
        var source = requireSubject(subjectType, sourceId, true);
        requireVersion(survivorVersion, survivor.version());
        requireVersion(sourceVersion, source.version());
        var conflicts = conflicts(subjectType, survivorId, sourceId);
        if (!conflicts.isEmpty()) {
            throw failure(CatalogRecoveryFailure.Code.INVALID_MERGE, conflicts.getFirst());
        }

        var before = snapshot(subjectType, survivorId, sourceId);
        switch (subjectType) {
            case WORK -> mergeWorks(survivorId, sourceId);
            case EDITION -> mergeEditions(survivorId, sourceId);
            case CONTRIBUTOR -> mergeContributors(survivorId, sourceId);
        }
        failureInjector.afterAssociationMoves();
        markMerged(subjectType, survivorId, sourceId);
        var after = snapshot(subjectType, survivorId, sourceId);
        var operationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog_merge_operation (
                    id, operation_type, subject_type, survivor_id, source_id,
                    survivor_version, source_version, actor_id, reason, idempotency_key,
                    before_state, after_state
                ) VALUES (
                    :id, :operationType, :subjectType, :survivorId, :sourceId,
                    :survivorVersion, :sourceVersion, :actorId, :reason, :idempotencyKey,
                    CAST(:beforeState AS jsonb), CAST(:afterState AS jsonb)
                )
                """)
                .param("id", operationId)
                .param("operationType", subjectType.name() + "_MERGE")
                .param("subjectType", subjectType.name())
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .param("survivorVersion", survivorVersion)
                .param("sourceVersion", sourceVersion)
                .param("actorId", actorId)
                .param("reason", normalizedReason)
                .param("idempotencyKey", idempotencyKey)
                .param("beforeState", json(before))
                .param("afterState", json(after))
                .update();
        jdbc.sql("""
                INSERT INTO catalog_redirect (
                    id, subject_type, former_id, canonical_id, reason, actor_id
                ) VALUES (:id, :subjectType, :sourceId, :survivorId, 'MERGE', :actorId)
                """)
                .param("id", UUID.randomUUID())
                .param("subjectType", subjectType.name())
                .param("sourceId", sourceId)
                .param("survivorId", survivorId)
                .param("actorId", actorId)
                .update();
        appendAudit(operationId, subjectType, survivorId, sourceId, actorId, normalizedReason, before, after, false);
        return requireOperation(operationId);
    }

    @Override
    public SplitPreview splitPreview(UUID operationId) {
        var operation = requireOperation(operationId);
        if (!"APPLIED".equals(operation.status())) {
            return new SplitPreview(operation, false, List.of("This merge has already been undone."));
        }
        var conflict = hasRecoveryConflict(operation);
        return new SplitPreview(
                operation,
                !conflict,
                conflict
                        ? List.of("The merged catalog state changed after this operation; use a guided split.")
                        : List.of());
    }

    @Override
    @Transactional
    public MergeOperation undo(UUID operationId, String reason, UUID actorId) {
        var normalizedReason = requireText(reason, "reason", 1_000);
        var operation = lockOperation(operationId);
        if (!"APPLIED".equals(operation.status()) || hasRecoveryConflict(operation)) {
            throw failure(
                    CatalogRecoveryFailure.Code.SPLIT_CONFLICT,
                    "Automatic undo is unsafe because the merged catalog state has changed.");
        }
        var before = readSnapshot(operationBeforeState(operationId));
        restore(operation.subjectType(), operation.survivorId(), operation.sourceId(), before);
        jdbc.sql("""
                UPDATE catalog_redirect
                SET active = FALSE, retired_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE subject_type = :subjectType AND former_id = :sourceId AND active
                """)
                .param("subjectType", operation.subjectType().name())
                .param("sourceId", operation.sourceId())
                .update();
        jdbc.sql("""
                UPDATE catalog_merge_operation
                SET status = 'UNDONE', undone_at = CURRENT_TIMESTAMP,
                    undone_by = :actorId, undo_reason = :reason
                WHERE id = :operationId
                """)
                .param("actorId", actorId)
                .param("reason", normalizedReason)
                .param("operationId", operationId)
                .update();
        appendAudit(
                operationId,
                operation.subjectType(),
                operation.survivorId(),
                operation.sourceId(),
                actorId,
                normalizedReason,
                readSnapshot(operationAfterState(operationId)),
                before,
                true);
        return requireOperation(operationId);
    }

    @Override
    @Transactional
    public DuplicateDecision markNotSame(
            SubjectType subjectType,
            UUID firstId,
            UUID secondId,
            String ruleName,
            String ruleVersion,
            String reason,
            UUID actorId) {
        requireDifferent(firstId, secondId);
        requireSubject(subjectType, firstId, false);
        requireSubject(subjectType, secondId, false);
        var firstPrecedesSecond = firstId.toString().compareTo(secondId.toString()) < 0;
        var orderedFirst = firstPrecedesSecond ? firstId : secondId;
        var orderedSecond = firstPrecedesSecond ? secondId : firstId;
        var normalizedRuleName = requireText(ruleName, "ruleName", 100);
        var normalizedRuleVersion = requireText(ruleVersion, "ruleVersion", 100);
        var normalizedReason = requireText(reason, "reason", 1_000);
        jdbc.sql("""
                INSERT INTO catalog_duplicate_decision (
                    id, subject_type, first_subject_id, second_subject_id, decision,
                    rule_name, rule_version, actor_id, reason
                ) VALUES (
                    :id, :subjectType, :firstId, :secondId, 'NOT_SAME',
                    :ruleName, :ruleVersion, :actorId, :reason
                ) ON CONFLICT (subject_type, first_subject_id, second_subject_id, rule_name, rule_version)
                DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("subjectType", subjectType.name())
                .param("firstId", orderedFirst)
                .param("secondId", orderedSecond)
                .param("ruleName", normalizedRuleName)
                .param("ruleVersion", normalizedRuleVersion)
                .param("actorId", actorId)
                .param("reason", normalizedReason)
                .update();
        return jdbc.sql("""
                SELECT id, subject_type, first_subject_id, second_subject_id,
                       rule_name, rule_version, reason, created_at
                FROM catalog_duplicate_decision
                WHERE subject_type = :subjectType
                  AND first_subject_id = :firstId AND second_subject_id = :secondId
                  AND rule_name = :ruleName AND rule_version = :ruleVersion
                """)
                .param("subjectType", subjectType.name())
                .param("firstId", orderedFirst)
                .param("secondId", orderedSecond)
                .param("ruleName", normalizedRuleName)
                .param("ruleVersion", normalizedRuleVersion)
                .query((rs, rowNum) -> new DuplicateDecision(
                        UUID.fromString(rs.getString("id")),
                        SubjectType.valueOf(rs.getString("subject_type")),
                        UUID.fromString(rs.getString("first_subject_id")),
                        UUID.fromString(rs.getString("second_subject_id")),
                        rs.getString("rule_name"),
                        rs.getString("rule_version"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()))
                .single();
    }

    @Override
    public List<MergeOperation> history(SubjectType subjectType, UUID subjectId) {
        requireSubjectOrRedirect(subjectType, subjectId);
        return jdbc.sql("""
                SELECT id, subject_type, survivor_id, source_id, status,
                       actor_id, reason, created_at, undone_at
                FROM catalog_merge_operation
                WHERE subject_type = :subjectType
                  AND (survivor_id = :subjectId OR source_id = :subjectId)
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("subjectType", subjectType.name())
                .param("subjectId", subjectId)
                .param("limit", MAXIMUM_HISTORY)
                .query((row, rowNumber) -> mapOperation(row))
                .list();
    }

    private SubjectSummary requireSubject(SubjectType type, UUID id, boolean lock) {
        var access = accessContext.current();
        var table = table(type);
        var name = type == SubjectType.WORK
                ? "provisional_title"
                : type == SubjectType.CONTRIBUTOR ? "display_name" : "id::text";
        var lockClause = lock ? " FOR UPDATE" : "";
        var visibility = visibility(type);
        return jdbc.sql("SELECT id, " + name + " AS display_name, version FROM " + table
                        + " WHERE id = :id AND merged_into_id IS NULL AND " + visibility + lockClause)
                .param("id", id)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(SubjectSummary.class)
                .optional()
                .orElseThrow(() ->
                        failure(CatalogRecoveryFailure.Code.SUBJECT_NOT_FOUND, "The catalog subject was not found."));
    }

    private void requireSubjectOrRedirect(SubjectType type, UUID id) {
        var canonicalId = jdbc.sql("SELECT COALESCE(merged_into_id, id) FROM " + table(type) + " WHERE id = :id")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .orElseThrow(() ->
                        failure(CatalogRecoveryFailure.Code.SUBJECT_NOT_FOUND, "The catalog subject was not found."));
        requireSubject(type, canonicalId, false);
    }

    private static String visibility(SubjectType type) {
        var linkedAssets =
                switch (type) {
                    case WORK -> """
                    SELECT asset.id
                    FROM edition JOIN asset ON asset.edition_id = edition.id
                    WHERE edition.work_id = work.id
                    """;
                    case EDITION -> """
                    SELECT asset.id FROM asset WHERE asset.edition_id = edition.id
                    """;
                    case CONTRIBUTOR -> """
                    SELECT asset.id
                    FROM work_contributor linked
                    JOIN edition ON edition.work_id = linked.work_id
                    JOIN asset ON asset.edition_id = edition.id
                    WHERE linked.contributor_id = contributor.id
                    UNION
                    SELECT asset.id
                    FROM edition_contributor linked
                    JOIN asset ON asset.edition_id = linked.edition_id
                    WHERE linked.contributor_id = contributor.id
                    """;
                };
        return "EXISTS (SELECT 1 FROM (" + linkedAssets + ") visible_asset "
                + "JOIN asset_location visible_location ON visible_location.asset_id = visible_asset.id "
                + "WHERE visible_location.availability = 'AVAILABLE') AND (:unrestricted OR NOT EXISTS ("
                + "SELECT 1 FROM (" + linkedAssets + ") hidden_asset "
                + "JOIN asset_location hidden_location ON hidden_location.asset_id = hidden_asset.id "
                + "JOIN user_root_deny denied ON denied.library_root_id = hidden_location.library_root_id "
                + "WHERE denied.user_id = :userId))";
    }

    private List<String> conflicts(SubjectType type, UUID survivorId, UUID sourceId) {
        if (type != SubjectType.EDITION) {
            return List.of();
        }
        var sameWork = jdbc.sql("""
                SELECT count(*) = 1
                FROM edition survivor
                JOIN edition source ON source.work_id = survivor.work_id
                WHERE survivor.id = :survivorId AND source.id = :sourceId
                """)
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .query(Boolean.class)
                .single();
        return sameWork ? List.of() : List.of("Editions can be merged only when they belong to the same work.");
    }

    private Impact impact(SubjectType type, UUID sourceId) {
        return switch (type) {
            case WORK ->
                new Impact(
                        count("edition", "work_id", sourceId),
                        countJoin("asset", "edition", "edition_id", "id", "work_id", sourceId),
                        countTyped("WORK", sourceId),
                        count("work_contributor", "work_id", sourceId),
                        count("work_tag", "work_id", sourceId),
                        count("user_work_read_state", "work_id", sourceId),
                        count("personal_collection_work", "work_id", sourceId),
                        0);
            case EDITION ->
                new Impact(
                        1,
                        count("asset", "edition_id", sourceId),
                        countTyped("EDITION", sourceId),
                        count("edition_contributor", "edition_id", sourceId),
                        0,
                        count("user_work_read_state", "completed_edition_id", sourceId),
                        0,
                        0);
            case CONTRIBUTOR ->
                new Impact(
                        0,
                        0,
                        countTyped("CONTRIBUTOR", sourceId),
                        count("work_contributor", "contributor_id", sourceId)
                                + count("edition_contributor", "contributor_id", sourceId),
                        0,
                        0,
                        0,
                        count("user_contributor_favorite", "contributor_id", sourceId));
        };
    }

    private long count(String table, String column, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private long countJoin(
            String table,
            String joined,
            String tableJoinColumn,
            String joinedIdColumn,
            String joinedFilterColumn,
            UUID joinedFilter) {
        return jdbc.sql("SELECT count(*) FROM " + table + " item JOIN " + joined + " parent ON item."
                        + tableJoinColumn + " = parent." + joinedIdColumn + " WHERE parent." + joinedFilterColumn
                        + " = :id")
                .param("id", joinedFilter)
                .query(Long.class)
                .single();
    }

    private long countTyped(String type, UUID id) {
        return jdbc.sql("SELECT count(*) FROM metadata_observation WHERE subject_type = :type AND subject_id = :id")
                .param("type", type)
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private void mergeWorks(UUID survivorId, UUID sourceId) {
        jdbc.sql("UPDATE edition SET work_id = :survivorId, version = version + 1 WHERE work_id = :sourceId")
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
        mergeWorkContributors(survivorId, sourceId);
        moveNonConflicting("work_tag", "work_id", "tag_id", survivorId, sourceId);
        mergeReadStates(survivorId, sourceId);
        jdbc.sql("""
                UPDATE personal_collection_work source
                SET work_id = :survivorId
                WHERE source.work_id = :sourceId
                  AND NOT EXISTS (
                      SELECT 1 FROM personal_collection_work existing
                      WHERE existing.collection_id = source.collection_id
                        AND existing.work_id = :survivorId
                  )
                  AND (source.position IS NULL OR NOT EXISTS (
                      SELECT 1 FROM personal_collection_work occupied
                      WHERE occupied.collection_id = source.collection_id
                        AND occupied.position = source.position
                        AND occupied.work_id <> source.work_id
                  ))
                """)
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
        jdbc.sql("UPDATE cover_job SET work_id = :survivorId WHERE work_id = :sourceId")
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
        jdbc.sql("UPDATE cover_derivative SET work_id = :survivorId WHERE work_id = :sourceId")
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
        jdbc.sql("""
                UPDATE work_cover_preference
                SET work_id = :survivorId
                WHERE work_id = :sourceId
                  AND NOT EXISTS (
                      SELECT 1 FROM work_cover_preference existing WHERE existing.work_id = :survivorId
                  )
                """)
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
    }

    private void mergeWorkContributors(UUID survivorId, UUID sourceId) {
        moveSubjectLinks("work_contributor", "work_id", survivorId, sourceId);
    }

    private void mergeReadStates(UUID survivorId, UUID sourceId) {
        jdbc.sql("""
                UPDATE user_work_read_state source
                SET work_id = :survivorId
                WHERE source.work_id = :sourceId
                  AND NOT EXISTS (
                      SELECT 1 FROM user_work_read_state existing
                      WHERE existing.user_id = source.user_id AND existing.work_id = :survivorId
                  )
                """)
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
    }

    private void mergeEditions(UUID survivorId, UUID sourceId) {
        jdbc.sql("UPDATE asset SET edition_id = :survivorId, version = version + 1 WHERE edition_id = :sourceId")
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
        mergeEditionContributors(survivorId, sourceId);
        jdbc.sql("""
                UPDATE edition_identifier source
                SET edition_id = :survivorId
                WHERE source.edition_id = :sourceId
                  AND NOT EXISTS (
                      SELECT 1 FROM edition_identifier existing
                      WHERE existing.edition_id = :survivorId
                        AND existing.scheme = source.scheme
                        AND existing.observed_value = source.observed_value
                  )
                """)
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
        jdbc.sql(
                        "UPDATE user_work_read_state SET completed_edition_id = :survivorId WHERE completed_edition_id = :sourceId")
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
    }

    private void mergeEditionContributors(UUID survivorId, UUID sourceId) {
        moveSubjectLinks("edition_contributor", "edition_id", survivorId, sourceId);
    }

    private void mergeContributors(UUID survivorId, UUID sourceId) {
        jdbc.sql("""
                UPDATE contributor_alias source
                SET contributor_id = :survivorId
                WHERE source.contributor_id = :sourceId
                  AND NOT EXISTS (
                      SELECT 1 FROM contributor_alias existing
                      WHERE existing.contributor_id = :survivorId
                        AND existing.normalized_alias = source.normalized_alias
                  )
                """)
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
        mergeContributorLinks("work_contributor", "work_id", survivorId, sourceId);
        mergeContributorLinks("edition_contributor", "edition_id", survivorId, sourceId);
        moveNonConflicting("user_contributor_favorite", "contributor_id", "user_id", survivorId, sourceId);
    }

    private void mergeContributorLinks(String table, String subjectColumn, UUID survivorId, UUID sourceId) {
        var sourceLinks = links(table, subjectColumn, null, sourceId);
        for (var link : sourceLinks) {
            var duplicate = jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + subjectColumn
                            + " = :subjectId AND contributor_id = :survivorId AND role = :role")
                    .param("subjectId", link.subjectId())
                    .param("survivorId", survivorId)
                    .param("role", link.role())
                    .query(Long.class)
                    .single();
            if (duplicate == 0) {
                var ordinal = nextOrdinal(table, subjectColumn, link.subjectId(), link.role());
                jdbc.sql("UPDATE " + table
                                + " SET contributor_id = :survivorId, ordinal = :ordinal WHERE " + subjectColumn
                                + " = :subjectId AND contributor_id = :sourceId AND role = :role")
                        .param("survivorId", survivorId)
                        .param("ordinal", ordinal)
                        .param("subjectId", link.subjectId())
                        .param("sourceId", sourceId)
                        .param("role", link.role())
                        .update();
            }
        }
    }

    private void moveSubjectLinks(String table, String subjectColumn, UUID survivorId, UUID sourceId) {
        var sourceLinks = links(table, subjectColumn, sourceId, null);
        for (var link : sourceLinks) {
            var duplicate = jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + subjectColumn
                            + " = :survivorId AND contributor_id = :contributorId AND role = :role")
                    .param("survivorId", survivorId)
                    .param("contributorId", link.contributorId())
                    .param("role", link.role())
                    .query(Long.class)
                    .single();
            if (duplicate == 0) {
                var ordinal = nextOrdinal(table, subjectColumn, survivorId, link.role());
                jdbc.sql("UPDATE " + table + " SET " + subjectColumn
                                + " = :survivorId, ordinal = :ordinal WHERE " + subjectColumn
                                + " = :sourceId AND contributor_id = :contributorId AND role = :role")
                        .param("survivorId", survivorId)
                        .param("ordinal", ordinal)
                        .param("sourceId", sourceId)
                        .param("contributorId", link.contributorId())
                        .param("role", link.role())
                        .update();
            }
        }
    }

    private int nextOrdinal(String table, String subjectColumn, UUID subjectId, String role) {
        return jdbc.sql("SELECT COALESCE(max(ordinal) + 1, 0) FROM " + table + " WHERE " + subjectColumn
                        + " = :subjectId AND role = :role")
                .param("subjectId", subjectId)
                .param("role", role)
                .query(Integer.class)
                .single();
    }

    private void moveNonConflicting(
            String table, String ownerColumn, String keyColumn, UUID survivorId, UUID sourceId) {
        jdbc.sql("UPDATE " + table + " source SET " + ownerColumn + " = :survivorId WHERE source."
                        + ownerColumn + " = :sourceId AND NOT EXISTS (SELECT 1 FROM " + table
                        + " existing WHERE existing." + ownerColumn + " = :survivorId AND existing."
                        + keyColumn + " = source." + keyColumn + ")")
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
    }

    private void markMerged(SubjectType type, UUID survivorId, UUID sourceId) {
        jdbc.sql("UPDATE " + table(type)
                        + " SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = :survivorId")
                .param("survivorId", survivorId)
                .update();
        jdbc.sql(
                        "UPDATE " + table(type)
                                + " SET merged_into_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = :sourceId")
                .param("survivorId", survivorId)
                .param("sourceId", sourceId)
                .update();
    }

    private PairSnapshot snapshot(SubjectType type, UUID survivorId, UUID sourceId) {
        return new PairSnapshot(subjectSnapshot(type, survivorId), subjectSnapshot(type, sourceId));
    }

    private SubjectSnapshot subjectSnapshot(SubjectType type, UUID id) {
        var row = jdbc.sql("SELECT version, merged_into_id FROM " + table(type) + " WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) ->
                        new SubjectIdentity(rs.getLong("version"), rs.getObject("merged_into_id", UUID.class)))
                .single();
        return new SubjectSnapshot(
                row.version(),
                row.mergedIntoId(),
                ids("edition", "id", "work_id", type == SubjectType.WORK ? id : null),
                ids("asset", "id", "edition_id", type == SubjectType.EDITION ? id : null),
                links(
                        "work_contributor",
                        "work_id",
                        type == SubjectType.WORK ? id : null,
                        type == SubjectType.CONTRIBUTOR ? id : null),
                links(
                        "edition_contributor",
                        "edition_id",
                        type == SubjectType.EDITION ? id : null,
                        type == SubjectType.CONTRIBUTOR ? id : null),
                ids("edition_identifier", "id", "edition_id", type == SubjectType.EDITION ? id : null),
                ids("work_tag", "tag_id", "work_id", type == SubjectType.WORK ? id : null),
                ids(
                        "user_work_read_state",
                        "user_id",
                        type == SubjectType.EDITION ? "completed_edition_id" : "work_id",
                        type == SubjectType.WORK || type == SubjectType.EDITION ? id : null),
                ids("personal_collection_work", "collection_id", "work_id", type == SubjectType.WORK ? id : null),
                ids("contributor_alias", "id", "contributor_id", type == SubjectType.CONTRIBUTOR ? id : null),
                ids(
                        "user_contributor_favorite",
                        "user_id",
                        "contributor_id",
                        type == SubjectType.CONTRIBUTOR ? id : null),
                ids("cover_job", "id", "work_id", type == SubjectType.WORK ? id : null),
                ids("cover_derivative", "id", "work_id", type == SubjectType.WORK ? id : null),
                ids("work_cover_preference", "source_asset_id", "work_id", type == SubjectType.WORK ? id : null),
                readStateFingerprints(type, id),
                collectionFingerprints(type, id),
                coverPreferenceFingerprints(type, id));
    }

    private List<UUID> ids(String table, String selectedColumn, String filterColumn, UUID id) {
        if (id == null) {
            return List.of();
        }
        return jdbc.sql("SELECT " + selectedColumn + " FROM " + table + " WHERE " + filterColumn + " = :id ORDER BY "
                        + selectedColumn)
                .param("id", id)
                .query(UUID.class)
                .list();
    }

    private List<LinkKey> links(String table, String subjectColumn, UUID subjectId, UUID contributorId) {
        if (subjectId == null && contributorId == null) {
            return List.of();
        }
        var filter = subjectId != null ? subjectColumn + " = :id" : "contributor_id = :id";
        return jdbc.sql("SELECT " + subjectColumn
                        + " AS subject_id, contributor_id, role, ordinal FROM " + table
                        + " WHERE " + filter + " ORDER BY " + subjectColumn + ", role, ordinal, contributor_id")
                .param("id", subjectId != null ? subjectId : contributorId)
                .query(LinkKey.class)
                .list();
    }

    private List<String> readStateFingerprints(SubjectType type, UUID id) {
        if (type != SubjectType.WORK && type != SubjectType.EDITION) {
            return List.of();
        }
        var column = type == SubjectType.WORK ? "work_id" : "completed_edition_id";
        return jdbc.sql("SELECT concat_ws('|', user_id, work_id, completed_edition_id, completed_at, version) "
                        + "FROM user_work_read_state WHERE " + column + " = :id ORDER BY user_id")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private List<String> collectionFingerprints(SubjectType type, UUID id) {
        if (type != SubjectType.WORK) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT concat_ws('|', collection_id, work_id, position, added_at)
                FROM personal_collection_work WHERE work_id = :id ORDER BY collection_id
                """).param("id", id).query(String.class).list();
    }

    private List<String> coverPreferenceFingerprints(SubjectType type, UUID id) {
        if (type != SubjectType.WORK) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT concat_ws('|', work_id, source_asset_id, actor_user_id, reason, version, updated_at)
                FROM work_cover_preference WHERE work_id = :id
                """).param("id", id).query(String.class).list();
    }

    private boolean hasRecoveryConflict(MergeOperation operation) {
        var expected = readSnapshot(operationAfterState(operation.id()));
        var current = snapshot(operation.subjectType(), operation.survivorId(), operation.sourceId());
        return !expected.equals(current);
    }

    private void restore(SubjectType type, UUID survivorId, UUID sourceId, PairSnapshot before) {
        switch (type) {
            case WORK -> restoreWork(survivorId, sourceId, before);
            case EDITION -> restoreEdition(survivorId, sourceId, before);
            case CONTRIBUTOR -> restoreContributor(survivorId, sourceId, before);
        }
        jdbc.sql(
                        "UPDATE " + table(type)
                                + " SET version = :version, merged_into_id = :mergedIntoId, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("version", before.survivor().version())
                .param("mergedIntoId", before.survivor().mergedIntoId())
                .param("id", survivorId)
                .update();
        jdbc.sql(
                        "UPDATE " + table(type)
                                + " SET version = :version, merged_into_id = :mergedIntoId, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("version", before.source().version())
                .param("mergedIntoId", before.source().mergedIntoId())
                .param("id", sourceId)
                .update();
    }

    private void restoreWork(UUID survivorId, UUID sourceId, PairSnapshot before) {
        for (var editionId : before.source().editionIds()) {
            jdbc.sql("UPDATE edition SET work_id = :sourceId, version = version + 1 WHERE id = :editionId")
                    .param("sourceId", sourceId)
                    .param("editionId", editionId)
                    .update();
        }
        restoreSimpleOwnership(
                "work_tag",
                "work_id",
                "tag_id",
                survivorId,
                sourceId,
                before.survivor().tagIds(),
                before.source().tagIds());
        restoreSimpleOwnership(
                "personal_collection_work",
                "work_id",
                "collection_id",
                survivorId,
                sourceId,
                before.survivor().collectionIds(),
                before.source().collectionIds());
        restoreSimpleOwnership(
                "user_work_read_state",
                "work_id",
                "user_id",
                survivorId,
                sourceId,
                before.survivor().readUserIds(),
                before.source().readUserIds());
        restoreSubjectLinks(
                "work_contributor",
                "work_id",
                survivorId,
                sourceId,
                before.survivor().workContributors(),
                before.source().workContributors());
        restoreSimpleOwnership(
                "cover_job",
                "work_id",
                "id",
                survivorId,
                sourceId,
                before.survivor().coverJobIds(),
                before.source().coverJobIds());
        restoreSimpleOwnership(
                "cover_derivative",
                "work_id",
                "id",
                survivorId,
                sourceId,
                before.survivor().coverDerivativeIds(),
                before.source().coverDerivativeIds());
        restoreCoverPreference(survivorId, sourceId, before);
    }

    private void restoreEdition(UUID survivorId, UUID sourceId, PairSnapshot before) {
        restoreSimpleOwnership(
                "asset",
                "edition_id",
                "id",
                survivorId,
                sourceId,
                before.survivor().assetIds(),
                before.source().assetIds());
        restoreSubjectLinks(
                "edition_contributor",
                "edition_id",
                survivorId,
                sourceId,
                before.survivor().editionContributors(),
                before.source().editionContributors());
        restoreSimpleOwnership(
                "edition_identifier",
                "edition_id",
                "id",
                survivorId,
                sourceId,
                before.survivor().identifierIds(),
                before.source().identifierIds());
        for (var userId : before.source().readUserIds()) {
            jdbc.sql(
                            "UPDATE user_work_read_state SET completed_edition_id = :sourceId WHERE user_id = :userId AND completed_edition_id = :survivorId")
                    .param("sourceId", sourceId)
                    .param("survivorId", survivorId)
                    .param("userId", userId)
                    .update();
        }
    }

    private void restoreContributor(UUID survivorId, UUID sourceId, PairSnapshot before) {
        restoreSimpleOwnership(
                "contributor_alias",
                "contributor_id",
                "id",
                survivorId,
                sourceId,
                before.survivor().aliasIds(),
                before.source().aliasIds());
        restoreContributorLinks(
                "work_contributor",
                "work_id",
                survivorId,
                sourceId,
                before.survivor().workContributors(),
                before.source().workContributors());
        restoreContributorLinks(
                "edition_contributor",
                "edition_id",
                survivorId,
                sourceId,
                before.survivor().editionContributors(),
                before.source().editionContributors());
        restoreSimpleOwnership(
                "user_contributor_favorite",
                "contributor_id",
                "user_id",
                survivorId,
                sourceId,
                before.survivor().favoriteUserIds(),
                before.source().favoriteUserIds());
    }

    private void restoreSimpleOwnership(
            String table,
            String ownerColumn,
            String keyColumn,
            UUID survivorId,
            UUID sourceId,
            List<UUID> survivorKeys,
            List<UUID> sourceKeys) {
        for (var key : sourceKeys) {
            if (!survivorKeys.contains(key)) {
                jdbc.sql("UPDATE " + table + " SET " + ownerColumn + " = :sourceId WHERE " + ownerColumn
                                + " = :survivorId AND " + keyColumn + " = :key")
                        .param("sourceId", sourceId)
                        .param("survivorId", survivorId)
                        .param("key", key)
                        .update();
            }
        }
    }

    private void restoreSubjectLinks(
            String table,
            String subjectColumn,
            UUID survivorId,
            UUID sourceId,
            List<LinkKey> survivorLinks,
            List<LinkKey> sourceLinks) {
        for (var sourceLink : sourceLinks) {
            var existedOnSurvivor = survivorLinks.stream()
                    .anyMatch(link -> link.contributorId().equals(sourceLink.contributorId())
                            && link.role().equals(sourceLink.role()));
            if (!existedOnSurvivor) {
                jdbc.sql("UPDATE " + table + " SET " + subjectColumn
                                + " = :sourceId, ordinal = :ordinal WHERE " + subjectColumn
                                + " = :survivorId AND contributor_id = :contributorId AND role = :role")
                        .param("sourceId", sourceId)
                        .param("ordinal", sourceLink.ordinal())
                        .param("survivorId", survivorId)
                        .param("contributorId", sourceLink.contributorId())
                        .param("role", sourceLink.role())
                        .update();
            }
        }
    }

    private void restoreContributorLinks(
            String table,
            String subjectColumn,
            UUID survivorId,
            UUID sourceId,
            List<LinkKey> survivorLinks,
            List<LinkKey> sourceLinks) {
        for (var sourceLink : sourceLinks) {
            var existedOnSurvivor = survivorLinks.stream()
                    .anyMatch(link -> link.subjectId().equals(sourceLink.subjectId())
                            && link.role().equals(sourceLink.role()));
            if (!existedOnSurvivor) {
                jdbc.sql("UPDATE " + table
                                + " SET contributor_id = :sourceId, ordinal = :ordinal WHERE " + subjectColumn
                                + " = :subjectId AND contributor_id = :survivorId AND role = :role")
                        .param("sourceId", sourceId)
                        .param("ordinal", sourceLink.ordinal())
                        .param("subjectId", sourceLink.subjectId())
                        .param("survivorId", survivorId)
                        .param("role", sourceLink.role())
                        .update();
            }
        }
    }

    private void restoreCoverPreference(UUID survivorId, UUID sourceId, PairSnapshot before) {
        var sourcePreferences = before.source().coverPreferenceAssetIds();
        if (!sourcePreferences.isEmpty()
                && before.survivor().coverPreferenceAssetIds().isEmpty()) {
            jdbc.sql("UPDATE work_cover_preference SET work_id = :sourceId WHERE work_id = :survivorId")
                    .param("sourceId", sourceId)
                    .param("survivorId", survivorId)
                    .update();
        }
    }

    private MergeOperation findByIdempotencyKey(UUID idempotencyKey) {
        return jdbc.sql("""
                SELECT id, subject_type, survivor_id, source_id, status,
                       actor_id, reason, created_at, undone_at
                FROM catalog_merge_operation WHERE idempotency_key = :idempotencyKey
                """)
                .param("idempotencyKey", idempotencyKey)
                .query((row, rowNumber) -> mapOperation(row))
                .optional()
                .orElse(null);
    }

    private MergeOperation requireOperation(UUID id) {
        return operation(id, false);
    }

    private MergeOperation lockOperation(UUID id) {
        return operation(id, true);
    }

    private MergeOperation operation(UUID id, boolean lock) {
        var lockClause = lock ? " FOR UPDATE" : "";
        return jdbc.sql("""
                SELECT id, subject_type, survivor_id, source_id, status,
                       actor_id, reason, created_at, undone_at
                FROM catalog_merge_operation WHERE id = :id
                """ + lockClause)
                .param("id", id)
                .query((row, rowNumber) -> mapOperation(row))
                .optional()
                .orElseThrow(() ->
                        failure(CatalogRecoveryFailure.Code.OPERATION_NOT_FOUND, "The merge operation was not found."));
    }

    private MergeOperation mapOperation(java.sql.ResultSet rs) throws java.sql.SQLException {
        var undoneAt = rs.getTimestamp("undone_at");
        return new MergeOperation(
                UUID.fromString(rs.getString("id")),
                SubjectType.valueOf(rs.getString("subject_type")),
                UUID.fromString(rs.getString("survivor_id")),
                UUID.fromString(rs.getString("source_id")),
                rs.getString("status"),
                UUID.fromString(rs.getString("actor_id")),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                undoneAt == null ? null : undoneAt.toInstant());
    }

    private String operationBeforeState(UUID id) {
        return operationState(id, "before_state");
    }

    private String operationAfterState(UUID id) {
        return operationState(id, "after_state");
    }

    private String operationState(UUID id, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM catalog_merge_operation WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private void appendAudit(
            UUID operationId,
            SubjectType type,
            UUID survivorId,
            UUID sourceId,
            UUID actorId,
            String reason,
            PairSnapshot before,
            PairSnapshot after,
            boolean undo) {
        jdbc.sql("""
                INSERT INTO catalog_audit_event (
                    id, aggregate_type, aggregate_id, event_type, actor_id,
                    correlation_id, reason, before_state, after_state
                ) VALUES (
                    :id, :aggregateType, :aggregateId, :eventType, :actorId,
                    :correlationId, :reason, CAST(:beforeState AS jsonb), CAST(:afterState AS jsonb)
                )
                """)
                .param("id", UUID.randomUUID())
                .param("aggregateType", type.name())
                .param("aggregateId", survivorId)
                .param("eventType", undo ? "CATALOG_MERGE_UNDONE" : "CATALOG_SUBJECTS_MERGED")
                .param("actorId", actorId)
                .param("correlationId", operationId.toString())
                .param("reason", reason + " [source=" + sourceId + "]")
                .param("beforeState", json(before))
                .param("afterState", json(after))
                .update();
    }

    private String json(PairSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Catalog recovery state could not be serialized.", exception);
        }
    }

    private PairSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, PairSnapshot.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Catalog recovery state could not be read.", exception);
        }
    }

    private static void requireDifferent(UUID first, UUID second) {
        if (Objects.equals(first, second)) {
            throw failure(CatalogRecoveryFailure.Code.INVALID_MERGE, "The survivor and source must be different.");
        }
    }

    private static void requireVersion(long expected, long actual) {
        if (expected != actual) {
            throw failure(
                    CatalogRecoveryFailure.Code.VERSION_CONFLICT, "The catalog subject changed; refresh and retry.");
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.strip().length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maximumLength + " characters");
        }
        return value.strip();
    }

    private static CatalogRecoveryFailure failure(CatalogRecoveryFailure.Code code, String message) {
        return new CatalogRecoveryFailure(code, message);
    }

    private static String table(SubjectType type) {
        return switch (type) {
            case WORK -> "work";
            case EDITION -> "edition";
            case CONTRIBUTOR -> "contributor";
        };
    }

    private record SubjectIdentity(long version, UUID mergedIntoId) {}

    private record LinkKey(UUID subjectId, UUID contributorId, String role, int ordinal) {}

    private record SubjectSnapshot(
            long version,
            UUID mergedIntoId,
            List<UUID> editionIds,
            List<UUID> assetIds,
            List<LinkKey> workContributors,
            List<LinkKey> editionContributors,
            List<UUID> identifierIds,
            List<UUID> tagIds,
            List<UUID> readUserIds,
            List<UUID> collectionIds,
            List<UUID> aliasIds,
            List<UUID> favoriteUserIds,
            List<UUID> coverJobIds,
            List<UUID> coverDerivativeIds,
            List<UUID> coverPreferenceAssetIds,
            List<String> readStateFingerprints,
            List<String> collectionFingerprints,
            List<String> coverPreferenceFingerprints) {}

    private record PairSnapshot(SubjectSnapshot survivor, SubjectSnapshot source) {}
}
