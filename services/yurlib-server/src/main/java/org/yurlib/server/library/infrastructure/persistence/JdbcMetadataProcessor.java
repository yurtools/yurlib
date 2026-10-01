package org.yurlib.server.library.infrastructure.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.domain.CatalogMetadataNormalizer;
import tools.jackson.databind.ObjectMapper;

@Component
final class JdbcMetadataProcessor {

    private static final int MAXIMUM_OPEN_REVIEW_ITEMS = 1_000;
    private static final String RESOLVER_NAME = "yurlib-deterministic-resolver";
    private static final String RESOLVER_VERSION = "1";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    JdbcMetadataProcessor(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.rebuild().build();
    }

    void process(UUID observationSetId, Instant processedAt) {
        var observations = jdbc.sql("""
                SELECT id, subject_id, subject_type, field_name, observed_value, value_ordinal
                FROM metadata_observation
                WHERE observation_set_id = :observationSetId
                  AND value_state = 'PRESENT'
                ORDER BY subject_type, subject_id, field_name, value_ordinal, id
                """)
                .param("observationSetId", observationSetId)
                .query(ObservationRow.class)
                .list();
        for (var observation : observations) {
            normalize(observation, processedAt);
        }
        observations.stream()
                .filter(observation -> resolvable(observation.fieldName()))
                .map(observation ->
                        new SubjectField(observation.subjectId(), observation.subjectType(), observation.fieldName()))
                .distinct()
                .forEach(subject -> resolve(subject, processedAt));
    }

    private void normalize(ObservationRow observation, Instant processedAt) {
        var result = CatalogMetadataNormalizer.normalize(observation.fieldName(), observation.observedValue());
        var factId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO metadata_normalized_fact (
                    id, source_observation_id, subject_id, subject_type, field_name,
                    value_state, normalized_value, value_type, normalizer_name,
                    normalizer_version, confidence, created_at
                ) VALUES (
                    :id, :observationId, :subjectId, :subjectType, :fieldName,
                    :valueState, :normalizedValue, :valueType, :normalizerName,
                    :normalizerVersion, :confidence, :createdAt
                )
                """)
                .param("id", factId)
                .param("observationId", observation.id())
                .param("subjectId", observation.subjectId())
                .param("subjectType", observation.subjectType())
                .param("fieldName", observation.fieldName())
                .param("valueState", result.valid() ? "PRESENT" : "ABSENT")
                .param("normalizedValue", result.normalizedValue())
                .param("valueType", result.valueType())
                .param("normalizerName", CatalogMetadataNormalizer.NAME)
                .param("normalizerVersion", CatalogMetadataNormalizer.VERSION)
                .param("confidence", result.confidence().name())
                .param("createdAt", Timestamp.from(processedAt))
                .update();
        if (!result.valid()) {
            enqueueReview(observation, "INVALID_VALUE", result.problem(), processedAt);
        } else if (result.valueType().startsWith("CONTRIBUTOR:")) {
            linkContributor(observation, result.normalizedValue(), result.valueType(), processedAt);
        }
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification =
                    "Alias matching uses deterministic locale-neutral case mapping while preserving the original display name.")
    private void linkContributor(
            ObservationRow observation, String displayName, String valueType, Instant processedAt) {
        if (!"WORK".equals(observation.subjectType())) {
            return;
        }
        var normalizedAlias = displayName.toLowerCase(Locale.ROOT);
        var contributorId = jdbc.sql("""
                SELECT alias.contributor_id
                FROM contributor_alias alias
                JOIN work_contributor linked ON linked.contributor_id = alias.contributor_id
                WHERE linked.work_id = :workId
                  AND alias.normalized_alias = :normalizedAlias
                ORDER BY alias.created_at, alias.id
                LIMIT 1
                """)
                .param("normalizedAlias", normalizedAlias)
                .param("workId", observation.subjectId())
                .query(UUID.class)
                .optional()
                .orElseGet(() -> insertContributor(displayName, normalizedAlias, observation.id(), processedAt));
        var role = valueType.substring("CONTRIBUTOR:".length());
        jdbc.sql("""
                INSERT INTO work_contributor (
                    work_id, contributor_id, role, ordinal, source_observation_id, created_at
                ) VALUES (
                    :workId, :contributorId, :role, :ordinal, :observationId, :createdAt
                )
                ON CONFLICT (work_id, role, ordinal) DO UPDATE
                SET contributor_id = EXCLUDED.contributor_id,
                    source_observation_id = EXCLUDED.source_observation_id,
                    version = work_contributor.version + 1
                """)
                .param("workId", observation.subjectId())
                .param("contributorId", contributorId)
                .param("role", role)
                .param("ordinal", observation.valueOrdinal())
                .param("observationId", observation.id())
                .param("createdAt", Timestamp.from(processedAt))
                .update();
    }

    private UUID insertContributor(
            String displayName, String normalizedAlias, UUID observationId, Instant processedAt) {
        var contributorId = UUID.randomUUID();
        var createdAt = Timestamp.from(processedAt);
        jdbc.sql("""
                INSERT INTO contributor (id, display_name, kind, created_at, updated_at)
                VALUES (:id, :displayName, 'UNRESOLVED', :createdAt, :createdAt)
                """)
                .param("id", contributorId)
                .param("displayName", displayName)
                .param("createdAt", createdAt)
                .update();
        jdbc.sql("""
                INSERT INTO contributor_alias (
                    id, contributor_id, alias, normalized_alias,
                    source_observation_id, created_at, updated_at
                ) VALUES (
                    :id, :contributorId, :alias, :normalizedAlias,
                    :observationId, :createdAt, :createdAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("contributorId", contributorId)
                .param("alias", displayName)
                .param("normalizedAlias", normalizedAlias)
                .param("observationId", observationId)
                .param("createdAt", createdAt)
                .update();
        return contributorId;
    }

    private void resolve(SubjectField subject, Instant processedAt) {
        var facts = jdbc.sql("""
                SELECT DISTINCT ON (normalized_value) id, normalized_value
                FROM metadata_normalized_fact
                WHERE subject_id = :subjectId
                  AND subject_type = :subjectType
                  AND field_name = :fieldName
                  AND value_state = 'PRESENT'
                  AND normalizer_name = :normalizerName
                  AND normalizer_version = :normalizerVersion
                ORDER BY normalized_value, created_at DESC, id DESC
                """)
                .param("subjectId", subject.subjectId())
                .param("subjectType", subject.subjectType())
                .param("fieldName", subject.fieldName())
                .param("normalizerName", CatalogMetadataNormalizer.NAME)
                .param("normalizerVersion", CatalogMetadataNormalizer.VERSION)
                .query(FactRow.class)
                .list();
        var outcome = facts.size() > 1 ? "CONFLICT" : facts.isEmpty() ? "ABSENT" : "PRESENT";
        var value = facts.size() == 1 ? facts.getFirst().normalizedValue() : null;
        var selectedFactId = facts.size() == 1 ? facts.getFirst().id() : null;
        var previous = activeResolution(subject);
        if (previous != null && previous.sameOutcome(outcome, value)) {
            return;
        }
        if (previous != null) {
            jdbc.sql("UPDATE metadata_resolved_value SET active = FALSE WHERE id = :id")
                    .param("id", previous.id())
                    .update();
        }
        var version = previous == null ? 1 : previous.version() + 1;
        jdbc.sql("""
                INSERT INTO metadata_resolved_value (
                    id, subject_id, subject_type, field_name, outcome, resolved_value,
                    selected_fact_id, alternatives, resolver_name, resolver_version,
                    confidence, resolution_version, supersedes_value_id, active, created_at
                ) VALUES (
                    :id, :subjectId, :subjectType, :fieldName, :outcome, :resolvedValue,
                    :selectedFactId, CAST(:alternatives AS jsonb), :resolverName, :resolverVersion,
                    :confidence, :resolutionVersion, :supersedesValueId, TRUE, :createdAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("subjectId", subject.subjectId())
                .param("subjectType", subject.subjectType())
                .param("fieldName", subject.fieldName())
                .param("outcome", outcome)
                .param("resolvedValue", value)
                .param("selectedFactId", selectedFactId)
                .param(
                        "alternatives",
                        objectMapper.writeValueAsString(
                                facts.stream().map(FactRow::id).toList()))
                .param("resolverName", RESOLVER_NAME)
                .param("resolverVersion", RESOLVER_VERSION)
                .param("confidence", facts.size() == 1 ? "HIGH" : "UNKNOWN")
                .param("resolutionVersion", version)
                .param("supersedesValueId", previous == null ? null : previous.id())
                .param("createdAt", Timestamp.from(processedAt))
                .update();
        if (facts.size() > 1) {
            enqueueConflict(subject, processedAt);
        }
    }

    private ResolutionRow activeResolution(SubjectField subject) {
        return jdbc.sql("""
                SELECT id, outcome, resolved_value, resolution_version
                FROM metadata_resolved_value
                WHERE subject_id = :subjectId
                  AND subject_type = :subjectType
                  AND field_name = :fieldName
                  AND active
                """)
                .param("subjectId", subject.subjectId())
                .param("subjectType", subject.subjectType())
                .param("fieldName", subject.fieldName())
                .query((row, rowNumber) -> new ResolutionRow(
                        row.getObject("id", UUID.class),
                        row.getString("outcome"),
                        row.getString("resolved_value"),
                        row.getLong("resolution_version")))
                .optional()
                .orElse(null);
    }

    private void enqueueReview(ObservationRow observation, String reasonCode, String detail, Instant processedAt) {
        enqueueReview(
                observation.subjectId(),
                observation.subjectType(),
                observation.fieldName(),
                observation.id(),
                reasonCode,
                detail,
                processedAt);
    }

    private void enqueueConflict(SubjectField subject, Instant processedAt) {
        enqueueReview(
                subject.subjectId(),
                subject.subjectType(),
                subject.fieldName(),
                null,
                "CONFLICT",
                "Multiple normalized values have equal resolution priority.",
                processedAt);
    }

    private void enqueueReview(
            UUID subjectId,
            String subjectType,
            String fieldName,
            UUID observationId,
            String reasonCode,
            String detail,
            Instant processedAt) {
        var openCount = jdbc.sql("SELECT count(*) FROM metadata_review_item WHERE status = 'OPEN'")
                .query(Long.class)
                .single();
        if (openCount >= MAXIMUM_OPEN_REVIEW_ITEMS) {
            return;
        }
        jdbc.sql("""
                INSERT INTO metadata_review_item (
                    id, subject_id, subject_type, field_name, source_observation_id,
                    reason_code, detail, rule_name, rule_version, created_at
                ) VALUES (
                    :id, :subjectId, :subjectType, :fieldName, :observationId,
                    :reasonCode, :detail, :ruleName, :ruleVersion, :createdAt
                )
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("subjectId", subjectId)
                .param("subjectType", subjectType)
                .param("fieldName", fieldName)
                .param("observationId", observationId)
                .param("reasonCode", reasonCode)
                .param("detail", detail)
                .param("ruleName", CatalogMetadataNormalizer.NAME)
                .param("ruleVersion", CatalogMetadataNormalizer.VERSION)
                .param("createdAt", Timestamp.from(processedAt))
                .update();
    }

    private static boolean resolvable(String fieldName) {
        return "title".equals(fieldName) || "language".equals(fieldName) || fieldName.startsWith("identifier:");
    }

    private record ObservationRow(
            UUID id, UUID subjectId, String subjectType, String fieldName, String observedValue, int valueOrdinal) {}

    private record SubjectField(UUID subjectId, String subjectType, String fieldName) {}

    private record FactRow(UUID id, String normalizedValue) {}

    private record ResolutionRow(UUID id, String outcome, String value, long version) {
        private boolean sameOutcome(String candidateOutcome, String candidateValue) {
            return outcome.equals(candidateOutcome) && java.util.Objects.equals(value, candidateValue);
        }
    }
}
