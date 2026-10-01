package org.yurlib.server.library.infrastructure.persistence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.CatalogCuration;
import org.yurlib.server.library.application.CatalogCurationFailure;
import org.yurlib.server.library.application.LibraryAccessContext;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcCatalogCuration implements CatalogCuration {

    private static final int MAXIMUM_TAGS_PER_WORK = 50;
    private static final int MAXIMUM_AUDIT_EVENTS = 100;

    private final JdbcClient jdbc;
    private final LibraryAccessContext accessContext;
    private final ObjectMapper objectMapper;

    public JdbcCatalogCuration(JdbcClient jdbc, LibraryAccessContext accessContext, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.accessContext = accessContext;
        this.objectMapper = objectMapper.rebuild().build();
    }

    @Override
    public WorkCuration findWork(UUID workId) {
        var work = requireVisibleWork(workId, false);
        return assemble(work);
    }

    @Override
    @Transactional
    public WorkCuration updateTitle(UUID workId, String value, String reason, long expectedVersion, UUID actorId) {
        var work = requireVisibleWork(workId, true);
        var normalizedValue = requireText(value, "title", 2_000);
        var normalizedReason = requireText(reason, "reason", 1_000);
        var latestVersion = latestOverrideVersion(workId);
        requireVersion(expectedVersion, latestVersion);
        var previous = activeOverride(workId);
        if (previous != null) {
            deactivateOverride(previous.id());
        }
        var overrideId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO metadata_curated_override (
                    id, subject_id, subject_type, field_name, value_state, curated_value,
                    actor_id, reason, override_version, supersedes_override_id, active
                ) VALUES (
                    :id, :workId, 'WORK', 'title', 'PRESENT', :value,
                    :actorId, :reason, :version, :supersedesId, TRUE
                )
                """)
                .param("id", overrideId)
                .param("workId", workId)
                .param("value", normalizedValue)
                .param("actorId", actorId)
                .param("reason", normalizedReason)
                .param("version", latestVersion + 1)
                .param("supersedesId", previous == null ? null : previous.id())
                .update();
        appendAudit(
                workId,
                "METADATA_OVERRIDE_UPDATED",
                actorId,
                normalizedReason,
                previous == null ? null : state(previous.value()),
                state(normalizedValue));
        resolveReviews(workId, "title", actorId, normalizedReason);
        return assemble(work);
    }

    @Override
    @Transactional
    public WorkCuration undoTitle(UUID workId, String reason, long expectedVersion, UUID actorId) {
        var work = requireVisibleWork(workId, true);
        var normalizedReason = requireText(reason, "reason", 1_000);
        var latestVersion = latestOverrideVersion(workId);
        requireVersion(expectedVersion, latestVersion);
        var current = activeOverride(workId);
        if (current == null) {
            throw versionConflict();
        }
        deactivateOverride(current.id());
        var prior = current.supersedesId() == null ? null : overrideById(current.supersedesId());
        if (prior != null) {
            jdbc.sql("""
                    INSERT INTO metadata_curated_override (
                        id, subject_id, subject_type, field_name, value_state, curated_value,
                        actor_id, reason, override_version, supersedes_override_id,
                        undo_of_override_id, active
                    ) VALUES (
                        :id, :workId, 'WORK', 'title', 'PRESENT', :value,
                        :actorId, :reason, :version, :supersedesId, :undoOfId, TRUE
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("workId", workId)
                    .param("value", prior.value())
                    .param("actorId", actorId)
                    .param("reason", normalizedReason)
                    .param("version", latestVersion + 1)
                    .param("supersedesId", current.id())
                    .param("undoOfId", current.id())
                    .update();
        }
        appendAudit(
                workId,
                "METADATA_OVERRIDE_UNDONE",
                actorId,
                normalizedReason,
                state(current.value()),
                prior == null ? null : state(prior.value()));
        return assemble(work);
    }

    @Override
    @Transactional
    public WorkCuration replaceTags(UUID workId, List<String> tags, String reason, long expectedVersion, UUID actorId) {
        var work = requireVisibleWork(workId, true);
        requireVersion(expectedVersion, work.version());
        var normalizedReason = requireText(reason, "reason", 1_000);
        var selectedTags = normalizeTags(tags);
        var before = findTags(workId);
        jdbc.sql("DELETE FROM work_tag WHERE work_id = :workId")
                .param("workId", workId)
                .update();
        for (var tag : selectedTags) {
            var tagId = findOrCreateTag(tag, actorId);
            jdbc.sql("""
                    INSERT INTO work_tag (work_id, tag_id, assigned_by)
                    VALUES (:workId, :tagId, :actorId)
                    """)
                    .param("workId", workId)
                    .param("tagId", tagId)
                    .param("actorId", actorId)
                    .update();
        }
        jdbc.sql("UPDATE work SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = :workId")
                .param("workId", workId)
                .update();
        appendAudit(workId, "SHARED_TAGS_REPLACED", actorId, normalizedReason, state(before), state(selectedTags));
        return assemble(new WorkRow(work.id(), work.version() + 1));
    }

    @Override
    @Transactional
    public ContributorCuration updateContributor(
            UUID contributorId,
            String displayName,
            List<String> aliases,
            String reason,
            long expectedVersion,
            UUID actorId) {
        var contributor = requireVisibleContributor(contributorId, true);
        requireVersion(expectedVersion, contributor.version());
        var normalizedDisplayName = requireText(displayName, "displayName", 1_000);
        var normalizedReason = requireText(reason, "reason", 1_000);
        var normalizedAliases = normalizeAliases(aliases);
        jdbc.sql("""
                UPDATE contributor
                SET display_name = :displayName, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :contributorId
                """)
                .param("displayName", normalizedDisplayName)
                .param("contributorId", contributorId)
                .update();
        jdbc.sql("""
                DELETE FROM contributor_alias
                WHERE contributor_id = :contributorId AND curated AND source_observation_id IS NULL
                """).param("contributorId", contributorId).update();
        for (var alias : normalizedAliases) {
            jdbc.sql("""
                    INSERT INTO contributor_alias (
                        id, contributor_id, alias, normalized_alias, version,
                        curated, actor_id, reason, created_at, updated_at
                    ) VALUES (
                        :id, :contributorId, :alias, :normalizedAlias, 0,
                        TRUE, :actorId, :reason, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    ON CONFLICT (contributor_id, normalized_alias) DO UPDATE
                    SET alias = EXCLUDED.alias,
                        version = contributor_alias.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    """)
                    .param("id", UUID.randomUUID())
                    .param("contributorId", contributorId)
                    .param("alias", alias)
                    .param("normalizedAlias", alias.toLowerCase(Locale.ROOT))
                    .param("actorId", actorId)
                    .param("reason", normalizedReason)
                    .update();
        }
        appendContributorAudit(
                contributorId,
                actorId,
                normalizedReason,
                state(contributor.displayName()),
                state(normalizedDisplayName));
        return findContributor(contributorId, contributor.role(), contributor.version() + 1);
    }

    @Override
    public List<ReviewItem> findOpenReviews(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        var access = accessContext.current();
        return jdbc.sql("""
                SELECT review.id, review.subject_id, review.subject_type, review.field_name,
                       review.reason_code, review.detail, review.rule_name, review.rule_version,
                       review.created_at
                FROM metadata_review_item review
                WHERE review.status = 'OPEN'
                  AND ((review.subject_type = 'WORK' AND EXISTS (
                      SELECT 1
                      FROM edition
                      JOIN asset ON asset.edition_id = edition.id
                      JOIN asset_location location ON location.asset_id = asset.id
                      WHERE edition.work_id = review.subject_id
                        AND location.availability = 'AVAILABLE'
                        AND (:unrestricted OR NOT EXISTS (
                            SELECT 1 FROM user_root_deny denied
                            WHERE denied.user_id = :userId
                              AND denied.library_root_id = location.library_root_id
                        ))
                  )) OR (review.subject_type = 'EDITION' AND EXISTS (
                      SELECT 1
                      FROM asset
                      JOIN asset_location location ON location.asset_id = asset.id
                      WHERE asset.edition_id = review.subject_id
                        AND location.availability = 'AVAILABLE'
                        AND (:unrestricted OR NOT EXISTS (
                            SELECT 1 FROM user_root_deny denied
                            WHERE denied.user_id = :userId
                              AND denied.library_root_id = location.library_root_id
                        ))
                  )) OR (review.subject_type = 'ASSET' AND EXISTS (
                      SELECT 1
                      FROM asset_location location
                      WHERE location.asset_id = review.subject_id
                        AND location.availability = 'AVAILABLE'
                        AND (:unrestricted OR NOT EXISTS (
                            SELECT 1 FROM user_root_deny denied
                            WHERE denied.user_id = :userId
                              AND denied.library_root_id = location.library_root_id
                        ))
                  )) OR (review.subject_type = 'CONTRIBUTOR' AND EXISTS (
                      SELECT 1
                      FROM work_contributor linked
                      JOIN edition ON edition.work_id = linked.work_id
                      JOIN asset ON asset.edition_id = edition.id
                      JOIN asset_location location ON location.asset_id = asset.id
                      WHERE linked.contributor_id = review.subject_id
                        AND location.availability = 'AVAILABLE'
                        AND (:unrestricted OR NOT EXISTS (
                            SELECT 1 FROM user_root_deny denied
                            WHERE denied.user_id = :userId
                              AND denied.library_root_id = location.library_root_id
                        ))
                  )))
                ORDER BY review.created_at, review.id
                LIMIT :limit
                """)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .param("limit", limit)
                .query(ReviewItem.class)
                .list();
    }

    @Override
    @Transactional
    public void dismissReview(UUID reviewId, String reason, UUID actorId) {
        var normalizedReason = requireText(reason, "reason", 1_000);
        requireVisibleReview(reviewId);
        var updated = jdbc.sql("""
                UPDATE metadata_review_item
                SET status = 'DISMISSED', resolved_by = :actorId,
                    resolution_reason = :reason, resolved_at = CURRENT_TIMESTAMP
                WHERE id = :reviewId AND status = 'OPEN'
                """)
                .param("actorId", actorId)
                .param("reason", normalizedReason)
                .param("reviewId", reviewId)
                .update();
        if (updated == 0) {
            throw new CatalogCurationFailure(
                    CatalogCurationFailure.Code.REVIEW_NOT_FOUND, "The review item was not found.");
        }
    }

    private void requireVisibleReview(UUID reviewId) {
        var access = accessContext.current();
        var visible = jdbc.sql("""
                SELECT count(*)
                FROM metadata_review_item review
                WHERE review.id = :reviewId AND review.status = 'OPEN'
                  AND ((review.subject_type = 'WORK' AND EXISTS (
                      SELECT 1
                      FROM edition
                      JOIN asset ON asset.edition_id = edition.id
                      JOIN asset_location location ON location.asset_id = asset.id
                      WHERE edition.work_id = review.subject_id
                        AND location.availability = 'AVAILABLE'
                        AND (:unrestricted OR NOT EXISTS (
                            SELECT 1 FROM user_root_deny denied
                            WHERE denied.user_id = :userId
                              AND denied.library_root_id = location.library_root_id
                        ))
                  )) OR (review.subject_type = 'EDITION' AND EXISTS (
                      SELECT 1
                      FROM asset
                      JOIN asset_location location ON location.asset_id = asset.id
                      WHERE asset.edition_id = review.subject_id
                        AND location.availability = 'AVAILABLE'
                        AND (:unrestricted OR NOT EXISTS (
                            SELECT 1 FROM user_root_deny denied
                            WHERE denied.user_id = :userId
                              AND denied.library_root_id = location.library_root_id
                        ))
                  )) OR (review.subject_type = 'ASSET' AND EXISTS (
                      SELECT 1
                      FROM asset_location location
                      WHERE location.asset_id = review.subject_id
                        AND location.availability = 'AVAILABLE'
                        AND (:unrestricted OR NOT EXISTS (
                            SELECT 1 FROM user_root_deny denied
                            WHERE denied.user_id = :userId
                              AND denied.library_root_id = location.library_root_id
                        ))
                  )) OR (review.subject_type = 'CONTRIBUTOR' AND EXISTS (
                      SELECT 1
                      FROM work_contributor linked
                      JOIN edition ON edition.work_id = linked.work_id
                      JOIN asset ON asset.edition_id = edition.id
                      JOIN asset_location location ON location.asset_id = asset.id
                      WHERE linked.contributor_id = review.subject_id
                        AND location.availability = 'AVAILABLE'
                        AND (:unrestricted OR NOT EXISTS (
                            SELECT 1 FROM user_root_deny denied
                            WHERE denied.user_id = :userId
                              AND denied.library_root_id = location.library_root_id
                        ))
                  )))
                """)
                .param("reviewId", reviewId)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(Long.class)
                .single();
        if (visible == 0) {
            throw new CatalogCurationFailure(
                    CatalogCurationFailure.Code.REVIEW_NOT_FOUND, "The review item was not found.");
        }
    }

    private WorkCuration assemble(WorkRow work) {
        var effective = jdbc.sql("""
                SELECT display_value AS value, metadata_source AS source
                FROM catalog_metadata_display
                WHERE subject_type = 'WORK' AND subject_id = :workId AND field_name = 'title'
                """)
                .param("workId", work.id())
                .query(EffectiveRow.class)
                .optional()
                .orElse(new EffectiveRow(null, "PROVISIONAL"));
        var observations =
                jdbc.sql("""
                SELECT DISTINCT observed_value
                FROM metadata_observation
                WHERE subject_type = 'WORK' AND subject_id = :workId
                  AND field_name = 'title' AND value_state = 'PRESENT'
                ORDER BY observed_value
                """).param("workId", work.id()).query(String.class).list();
        var history = jdbc.sql("""
                SELECT id, curated_value AS value, value_state, actor_id, reason,
                       override_version AS version, active, created_at
                FROM metadata_curated_override
                WHERE subject_type = 'WORK' AND subject_id = :workId AND field_name = 'title'
                ORDER BY override_version DESC
                """)
                .param("workId", work.id())
                .query(OverrideHistory.class)
                .list();
        var overrideVersion = history.isEmpty() ? 0 : history.getFirst().version();
        return new WorkCuration(
                work.id(),
                work.version(),
                new MetadataField(effective.value(), effective.source(), overrideVersion, observations, history),
                findContributors(work.id()),
                findTags(work.id()),
                findReviews(work.id()),
                findAudit(work.id()));
    }

    private List<ContributorCuration> findContributors(UUID workId) {
        return jdbc.sql("""
                SELECT contributor.id, contributor.display_name, linked.role, contributor.version
                FROM work_contributor linked
                JOIN contributor ON contributor.id = linked.contributor_id
                WHERE linked.work_id = :workId
                ORDER BY linked.role, linked.ordinal, contributor.id
                """).param("workId", workId).query(ContributorRow.class).list().stream()
                .map(row -> findContributor(row.id(), row.role(), row.version()))
                .toList();
    }

    private ContributorCuration findContributor(UUID contributorId, String role, long version) {
        var displayName = jdbc.sql("SELECT display_name FROM contributor WHERE id = :id")
                .param("id", contributorId)
                .query(String.class)
                .single();
        var aliases =
                jdbc.sql("""
                SELECT alias
                FROM contributor_alias
                WHERE contributor_id = :id
                ORDER BY normalized_alias, id
                """).param("id", contributorId).query(String.class).list();
        return new ContributorCuration(contributorId, displayName, role, version, aliases);
    }

    private ContributorRow requireVisibleContributor(UUID contributorId, boolean lock) {
        var access = accessContext.current();
        var lockClause = lock ? " FOR UPDATE OF contributor" : "";
        return jdbc.sql("""
                SELECT contributor.id, contributor.display_name,
                       (SELECT min(role) FROM work_contributor WHERE contributor_id = contributor.id) AS role,
                       contributor.version
                FROM contributor
                WHERE contributor.id = :contributorId
                  AND EXISTS (
                    SELECT 1
                    FROM work_contributor linked
                    JOIN edition ON edition.work_id = linked.work_id
                    JOIN asset ON asset.edition_id = edition.id
                    JOIN asset_location location ON location.asset_id = asset.id
                    WHERE linked.contributor_id = contributor.id
                      AND location.availability = 'AVAILABLE'
                      AND (:unrestricted OR NOT EXISTS (
                          SELECT 1 FROM user_root_deny denied
                          WHERE denied.user_id = :userId
                            AND denied.library_root_id = location.library_root_id
                      ))
                  )
                """ + lockClause)
                .param("contributorId", contributorId)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(ContributorRow.class)
                .optional()
                .orElseThrow(() -> new CatalogCurationFailure(
                        CatalogCurationFailure.Code.CONTRIBUTOR_NOT_FOUND, "The catalog contributor was not found."));
    }

    private WorkRow requireVisibleWork(UUID workId, boolean lock) {
        var access = accessContext.current();
        var lockClause = lock ? " FOR UPDATE OF work" : "";
        return jdbc.sql("""
                SELECT work.id, work.version
                FROM work
                WHERE work.id = :workId
                  AND EXISTS (
                    SELECT 1
                    FROM edition
                    JOIN asset ON asset.edition_id = edition.id
                    JOIN asset_location location ON location.asset_id = asset.id
                    WHERE edition.work_id = work.id
                      AND location.availability = 'AVAILABLE'
                      AND (:unrestricted OR NOT EXISTS (
                          SELECT 1 FROM user_root_deny denied
                          WHERE denied.user_id = :userId
                            AND denied.library_root_id = location.library_root_id
                      ))
                  )
                """ + lockClause)
                .param("workId", workId)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(WorkRow.class)
                .optional()
                .orElseThrow(() -> new CatalogCurationFailure(
                        CatalogCurationFailure.Code.WORK_NOT_FOUND, "The catalog work was not found."));
    }

    private List<String> findTags(UUID workId) {
        return jdbc.sql("""
                SELECT tag.name
                FROM catalog_tag tag
                JOIN work_tag assigned ON assigned.tag_id = tag.id
                WHERE assigned.work_id = :workId
                ORDER BY tag.normalized_name, tag.id
                """).param("workId", workId).query(String.class).list();
    }

    private List<ReviewItem> findReviews(UUID workId) {
        return jdbc.sql("""
                SELECT id, subject_id, subject_type, field_name, reason_code,
                       detail, rule_name, rule_version, created_at
                FROM metadata_review_item
                WHERE subject_type = 'WORK' AND subject_id = :workId AND status = 'OPEN'
                ORDER BY created_at, id
                """).param("workId", workId).query(ReviewItem.class).list();
    }

    private List<AuditEvent> findAudit(UUID workId) {
        return jdbc.sql("""
                SELECT id, event_type, actor_id, reason, created_at
                FROM catalog_audit_event
                WHERE (aggregate_type = 'WORK' AND aggregate_id = :workId)
                   OR (aggregate_type = 'CONTRIBUTOR' AND aggregate_id IN (
                       SELECT contributor_id FROM work_contributor WHERE work_id = :workId
                   ))
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("workId", workId)
                .param("limit", MAXIMUM_AUDIT_EVENTS)
                .query(AuditEvent.class)
                .list();
    }

    private OverrideRow activeOverride(UUID workId) {
        return jdbc.sql("""
                SELECT id, curated_value, supersedes_override_id
                FROM metadata_curated_override
                WHERE subject_type = 'WORK' AND subject_id = :workId
                  AND field_name = 'title' AND active
                """)
                .param("workId", workId)
                .query(OverrideRow.class)
                .optional()
                .orElse(null);
    }

    private OverrideRow overrideById(UUID overrideId) {
        return jdbc.sql("""
                SELECT id, curated_value, supersedes_override_id
                FROM metadata_curated_override
                WHERE id = :id
                """)
                .param("id", overrideId)
                .query(OverrideRow.class)
                .optional()
                .orElse(null);
    }

    private long latestOverrideVersion(UUID workId) {
        return jdbc.sql("""
                SELECT COALESCE(max(override_version), 0)
                FROM metadata_curated_override
                WHERE subject_type = 'WORK' AND subject_id = :workId AND field_name = 'title'
                """).param("workId", workId).query(Long.class).single();
    }

    private void deactivateOverride(UUID overrideId) {
        jdbc.sql("UPDATE metadata_curated_override SET active = FALSE WHERE id = :id")
                .param("id", overrideId)
                .update();
    }

    private UUID findOrCreateTag(String name, UUID actorId) {
        var normalized = name.toLowerCase(Locale.ROOT);
        var existing = jdbc.sql("SELECT id FROM catalog_tag WHERE normalized_name = :normalized")
                .param("normalized", normalized)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog_tag (id, name, normalized_name, created_by)
                VALUES (:id, :name, :normalized, :actorId)
                """)
                .param("id", id)
                .param("name", name)
                .param("normalized", normalized)
                .param("actorId", actorId)
                .update();
        return id;
    }

    private void resolveReviews(UUID workId, String fieldName, UUID actorId, String reason) {
        jdbc.sql("""
                UPDATE metadata_review_item
                SET status = 'RESOLVED', resolved_by = :actorId,
                    resolution_reason = :reason, resolved_at = CURRENT_TIMESTAMP
                WHERE subject_type = 'WORK' AND subject_id = :workId
                  AND field_name = :fieldName AND status = 'OPEN'
                """)
                .param("actorId", actorId)
                .param("reason", reason)
                .param("workId", workId)
                .param("fieldName", fieldName)
                .update();
    }

    private void appendAudit(UUID workId, String eventType, UUID actorId, String reason, String before, String after) {
        jdbc.sql("""
                INSERT INTO catalog_audit_event (
                    id, aggregate_type, aggregate_id, event_type, actor_id,
                    correlation_id, reason, before_state, after_state
                ) VALUES (
                    :id, 'WORK', :workId, :eventType, :actorId,
                    :correlationId, :reason, CAST(:before AS jsonb), CAST(:after AS jsonb)
                )
                """)
                .param("id", UUID.randomUUID())
                .param("workId", workId)
                .param("eventType", eventType)
                .param("actorId", actorId)
                .param("correlationId", UUID.randomUUID().toString())
                .param("reason", reason)
                .param("before", before)
                .param("after", after)
                .update();
    }

    private void appendContributorAudit(UUID contributorId, UUID actorId, String reason, String before, String after) {
        jdbc.sql("""
                INSERT INTO catalog_audit_event (
                    id, aggregate_type, aggregate_id, event_type, actor_id,
                    correlation_id, reason, before_state, after_state
                ) VALUES (
                    :id, 'CONTRIBUTOR', :contributorId, 'CONTRIBUTOR_ALIASES_UPDATED', :actorId,
                    :correlationId, :reason, CAST(:before AS jsonb), CAST(:after AS jsonb)
                )
                """)
                .param("id", UUID.randomUUID())
                .param("contributorId", contributorId)
                .param("actorId", actorId)
                .param("correlationId", UUID.randomUUID().toString())
                .param("reason", reason)
                .param("before", before)
                .param("after", after)
                .update();
    }

    private String state(Object value) {
        return objectMapper.writeValueAsString(java.util.Map.of("value", value));
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            throw new IllegalArgumentException("tags are required");
        }
        var normalized = new LinkedHashSet<String>();
        for (var tag : tags) {
            normalized.add(requireText(tag, "tag", 100));
        }
        if (normalized.size() > MAXIMUM_TAGS_PER_WORK) {
            throw new IllegalArgumentException("a work may have at most 50 tags");
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeAliases(List<String> aliases) {
        if (aliases == null) {
            throw new IllegalArgumentException("aliases are required");
        }
        var normalized = new LinkedHashSet<String>();
        for (var alias : aliases) {
            normalized.add(requireText(alias, "alias", 1_000));
        }
        if (normalized.size() > 100) {
            throw new IllegalArgumentException("a contributor may have at most 100 curated aliases");
        }
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be present and at most " + maximumLength + " characters");
        }
        return value.strip().replaceAll("(?U)\\s+", " ");
    }

    private static void requireVersion(long expected, long actual) {
        if (expected != actual) {
            throw versionConflict();
        }
    }

    private static CatalogCurationFailure versionConflict() {
        return new CatalogCurationFailure(
                CatalogCurationFailure.Code.VERSION_CONFLICT,
                "The catalog record changed. Reload it before saving again.");
    }

    private record WorkRow(UUID id, long version) {}

    private record EffectiveRow(String value, String source) {}

    private record OverrideRow(UUID id, String value, UUID supersedesId) {}

    private record ContributorRow(UUID id, String displayName, String role, long version) {}
}
