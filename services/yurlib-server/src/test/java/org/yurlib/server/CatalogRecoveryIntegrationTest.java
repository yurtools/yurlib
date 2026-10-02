package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.CatalogRecovery;
import org.yurlib.server.library.application.CatalogRecoveryFailure;
import org.yurlib.server.library.application.CatalogRecoveryFailureInjector;
import org.yurlib.server.library.application.LibraryAccessContext;
import org.yurlib.server.library.infrastructure.persistence.JdbcCatalogRecovery;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class CatalogRecoveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CatalogRecovery recovery;

    @MockitoBean
    private CatalogRecoveryFailureInjector failureInjector;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetFailureInjector() {
        reset(failureInjector);
    }

    @Test
    void previewsMergesRetriesAndUndoesAWorkWithoutChangingObservations() {
        var fixture = seedFixture();

        var preview = recovery.preview(CatalogRecovery.SubjectType.WORK, fixture.survivorWork(), fixture.sourceWork());
        assertThat(preview.mergeAllowed()).isTrue();
        assertThat(preview.impact().editions()).isEqualTo(1);
        assertThat(preview.impact().assets()).isEqualTo(1);
        assertThat(preview.impact().observations()).isEqualTo(1);
        assertThat(preview.impact().personalReadStates()).isEqualTo(1);

        var idempotencyKey = UUID.randomUUID();
        var operation = recovery.merge(
                CatalogRecovery.SubjectType.WORK,
                fixture.survivorWork(),
                fixture.sourceWork(),
                0,
                0,
                idempotencyKey,
                "Confirmed duplicate titles and identifiers",
                fixture.actor());

        assertThat(recovery.merge(
                        CatalogRecovery.SubjectType.WORK,
                        fixture.survivorWork(),
                        fixture.sourceWork(),
                        0,
                        0,
                        idempotencyKey,
                        "Retry",
                        fixture.actor()))
                .isEqualTo(operation);
        assertThat(uuid("SELECT merged_into_id FROM work WHERE id = :id", fixture.sourceWork()))
                .isEqualTo(fixture.survivorWork());
        assertThat(uuid("SELECT work_id FROM edition WHERE id = :id", fixture.sourceEdition()))
                .isEqualTo(fixture.survivorWork());
        assertThat(uuid("SELECT work_id FROM user_work_read_state WHERE user_id = :id", fixture.reader()))
                .isEqualTo(fixture.survivorWork());
        assertThat(count("SELECT count(*) FROM metadata_observation WHERE subject_id = :id", fixture.sourceWork()))
                .isEqualTo(1);
        assertThat(recovery.splitPreview(operation.id()).automaticUndoAllowed()).isTrue();

        var undone = recovery.undo(operation.id(), "The records are distinct works", fixture.actor());
        assertThat(undone.status()).isEqualTo("UNDONE");
        assertThat(uuid("SELECT work_id FROM edition WHERE id = :id", fixture.sourceEdition()))
                .isEqualTo(fixture.sourceWork());
        assertThat(uuid("SELECT work_id FROM user_work_read_state WHERE user_id = :id", fixture.reader()))
                .isEqualTo(fixture.sourceWork());
        assertThat(count(
                        "SELECT count(*) FROM catalog_redirect WHERE former_id = :id AND active", fixture.sourceWork()))
                .isZero();
    }

    @Test
    void requiresGuidedSplitAfterAConflictingEditAndScopesNotSameToRuleVersion() {
        var fixture = seedFixture();
        var operation = recovery.merge(
                CatalogRecovery.SubjectType.WORK,
                fixture.survivorWork(),
                fixture.sourceWork(),
                0,
                0,
                UUID.randomUUID(),
                "Probable duplicate",
                fixture.actor());
        jdbc.sql("UPDATE work SET version = version + 1 WHERE id = :id")
                .param("id", fixture.survivorWork())
                .update();

        assertThat(recovery.splitPreview(operation.id()).automaticUndoAllowed()).isFalse();
        assertThatThrownBy(() -> recovery.undo(operation.id(), "Undo", fixture.actor()))
                .isInstanceOf(CatalogRecoveryFailure.class)
                .extracting(failure -> ((CatalogRecoveryFailure) failure).code())
                .isEqualTo(CatalogRecoveryFailure.Code.SPLIT_CONFLICT);

        var first = recovery.markNotSame(
                CatalogRecovery.SubjectType.EDITION,
                fixture.survivorEdition(),
                fixture.sourceEdition(),
                "metadata-similarity",
                "1",
                "Different publication evidence",
                fixture.actor());
        var retry = recovery.markNotSame(
                CatalogRecovery.SubjectType.EDITION,
                fixture.sourceEdition(),
                fixture.survivorEdition(),
                "metadata-similarity",
                "1",
                "Retry",
                fixture.actor());
        var nextRuleVersion = recovery.markNotSame(
                CatalogRecovery.SubjectType.EDITION,
                fixture.survivorEdition(),
                fixture.sourceEdition(),
                "metadata-similarity",
                "2",
                "Re-evaluated candidate",
                fixture.actor());
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(nextRuleVersion.id()).isNotEqualTo(first.id());
    }

    @Test
    void supportsRepeatedRecoveryAndRequiresGuidedSplitAfterALaterCuratedTitle() {
        var fixture = seedFixture();

        var firstMerge = mergeWork(fixture, "First duplicate decision");
        assertThat(recovery.undo(firstMerge.id(), "Restore for another review", fixture.actor())
                        .status())
                .isEqualTo("UNDONE");

        var secondMerge = mergeWork(fixture, "Second duplicate decision");
        assertThat(recovery.undo(secondMerge.id(), "Restore again", fixture.actor())
                        .status())
                .isEqualTo("UNDONE");

        var thirdMerge = mergeWork(fixture, "Third duplicate decision");
        jdbc.sql("""
                INSERT INTO metadata_curated_override (
                    id, subject_id, subject_type, field_name, value_state, curated_value,
                    actor_id, reason, override_version, active
                ) VALUES (
                    :id, :workId, 'WORK', 'title', 'PRESENT', 'Later curated title',
                    :actorId, 'Verified after merge', 1, TRUE
                )
                """)
                .param("id", UUID.randomUUID())
                .param("workId", fixture.survivorWork())
                .param("actorId", fixture.actor())
                .update();

        var splitPreview = recovery.splitPreview(thirdMerge.id());
        assertThat(splitPreview.automaticUndoAllowed()).isFalse();
        assertThat(splitPreview.conflicts())
                .containsExactly("The merged catalog state changed after this operation; use a guided split.");
        assertThatThrownBy(() -> recovery.undo(thirdMerge.id(), "Unsafe undo", fixture.actor()))
                .isInstanceOf(CatalogRecoveryFailure.class)
                .extracting(failure -> ((CatalogRecoveryFailure) failure).code())
                .isEqualTo(CatalogRecoveryFailure.Code.SPLIT_CONFLICT);
        assertThat(count(
                        "SELECT count(*) FROM catalog_redirect WHERE former_id = :id AND active", fixture.sourceWork()))
                .isEqualTo(1);
        assertThat(count("SELECT max(version) FROM catalog_redirect WHERE former_id = :id", fixture.sourceWork()))
                .isEqualTo(4);
    }

    @Test
    void rollsBackEveryMoveWhenRecoveryFailsMidTransaction() {
        var fixture = seedFixture();
        doThrow(new IllegalStateException("injected failure"))
                .when(failureInjector)
                .afterAssociationMoves();

        assertThatThrownBy(() -> recovery.merge(
                        CatalogRecovery.SubjectType.WORK,
                        fixture.survivorWork(),
                        fixture.sourceWork(),
                        0,
                        0,
                        UUID.randomUUID(),
                        "Exercise rollback",
                        fixture.actor()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected failure");

        assertThat(uuid("SELECT work_id FROM edition WHERE id = :id", fixture.sourceEdition()))
                .isEqualTo(fixture.sourceWork());
        assertThat(uuid("SELECT work_id FROM user_work_read_state WHERE user_id = :id", fixture.reader()))
                .isEqualTo(fixture.sourceWork());
        assertThat(count("SELECT count(*) FROM catalog_merge_operation WHERE source_id = :id", fixture.sourceWork()))
                .isZero();
    }

    @Test
    void returnsNotFoundWithoutLeakingEvidenceFromADeniedRoot() {
        var fixture = seedFixture();
        jdbc.sql("INSERT INTO user_root_deny (user_id, library_root_id) VALUES (:userId, :rootId)")
                .param("userId", fixture.actor())
                .param("rootId", fixture.root())
                .update();
        var restrictedRecovery = new JdbcCatalogRecovery(
                jdbc, () -> new LibraryAccessContext.Access(fixture.actor(), false), () -> {}, objectMapper);

        assertThatThrownBy(() -> restrictedRecovery.preview(
                        CatalogRecovery.SubjectType.WORK, fixture.survivorWork(), fixture.sourceWork()))
                .isInstanceOf(CatalogRecoveryFailure.class)
                .extracting(failure -> ((CatalogRecoveryFailure) failure).code())
                .isEqualTo(CatalogRecoveryFailure.Code.SUBJECT_NOT_FOUND);
    }

    @Test
    void mergesAndUndoesEditionsAndContributorsWithStableSurvivors() {
        var fixture = seedFixture();
        var survivorContributor = UUID.randomUUID();
        var sourceContributor = UUID.randomUUID();
        jdbc.sql("INSERT INTO contributor (id, display_name) VALUES (:id, 'Survivor author')")
                .param("id", survivorContributor)
                .update();
        jdbc.sql("INSERT INTO contributor (id, display_name) VALUES (:id, 'Source author')")
                .param("id", sourceContributor)
                .update();
        jdbc.sql("""
                INSERT INTO work_contributor (work_id, contributor_id, role, ordinal)
                VALUES (:workId, :contributorId, 'AUTHOR', 0)
                """)
                .param("workId", fixture.survivorWork())
                .param("contributorId", survivorContributor)
                .update();
        jdbc.sql("""
                INSERT INTO work_contributor (work_id, contributor_id, role, ordinal)
                VALUES (:workId, :contributorId, 'AUTHOR', 0)
                """)
                .param("workId", fixture.sourceWork())
                .param("contributorId", sourceContributor)
                .update();

        var contributorMerge = recovery.merge(
                CatalogRecovery.SubjectType.CONTRIBUTOR,
                survivorContributor,
                sourceContributor,
                0,
                0,
                UUID.randomUUID(),
                "Same author identity",
                fixture.actor());
        assertThat(uuid("SELECT merged_into_id FROM contributor WHERE id = :id", sourceContributor))
                .isEqualTo(survivorContributor);
        assertThat(recovery.undo(contributorMerge.id(), "Distinct authors", fixture.actor())
                        .status())
                .isEqualTo("UNDONE");

        recovery.merge(
                CatalogRecovery.SubjectType.WORK,
                fixture.survivorWork(),
                fixture.sourceWork(),
                0,
                0,
                UUID.randomUUID(),
                "Prepare same-work edition review",
                fixture.actor());
        var editionMerge = recovery.merge(
                CatalogRecovery.SubjectType.EDITION,
                fixture.survivorEdition(),
                fixture.sourceEdition(),
                0,
                1,
                UUID.randomUUID(),
                "Same publication evidence",
                fixture.actor());
        assertThat(uuid("SELECT edition_id FROM asset WHERE id = :id", fixture.sourceAsset()))
                .isEqualTo(fixture.survivorEdition());
        assertThat(recovery.undo(editionMerge.id(), "Distinct editions", fixture.actor())
                        .status())
                .isEqualTo("UNDONE");
        assertThat(uuid("SELECT edition_id FROM asset WHERE id = :id", fixture.sourceAsset()))
                .isEqualTo(fixture.sourceEdition());
    }

    private Fixture seedFixture() {
        var actor = UUID.randomUUID();
        var reader = UUID.randomUUID();
        var root = UUID.randomUUID();
        var survivorWork = UUID.randomUUID();
        var survivorEdition = UUID.randomUUID();
        var sourceWork = UUID.randomUUID();
        var sourceEdition = UUID.randomUUID();
        var sourceAsset = UUID.randomUUID();
        seedUser(actor, "actor-" + actor, false);
        seedUser(reader, "reader-" + reader, false);
        jdbc.sql("""
                INSERT INTO library_root (
                    id, name, mount_alias, relative_base_path, expected_identity_digest, mode, availability
                ) VALUES (:id, 'Recovery root', :alias, '', :digest, 'READ_ONLY_SOURCE', 'AVAILABLE')
                """)
                .param("id", root)
                .param("alias", "recovery-" + root)
                .param("digest", "a".repeat(64))
                .update();
        seedWork(root, survivorWork, survivorEdition, UUID.randomUUID(), "Survivor", "survivor.epub");
        seedWork(root, sourceWork, sourceEdition, sourceAsset, "Source", "source.epub");
        jdbc.sql("""
                INSERT INTO user_work_read_state (
                    user_id, work_id, completed_edition_id, completed_at
                ) VALUES (:userId, :workId, :editionId, :completedAt)
                """)
                .param("userId", reader)
                .param("workId", sourceWork)
                .param("editionId", sourceEdition)
                .param("completedAt", Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")))
                .update();
        var observationSet = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO metadata_observation_set (
                    id, source_asset_id, source_root_id, parser_name, parser_version,
                    extraction_version, observed_at
                ) VALUES (:id, :assetId, :rootId, 'fixture', '1', '1', CURRENT_TIMESTAMP)
                """)
                .param("id", observationSet)
                .param("assetId", sourceAsset)
                .param("rootId", root)
                .update();
        jdbc.sql("""
                INSERT INTO metadata_observation (
                    id, subject_id, subject_type, field_name, observed_value,
                    parser_name, parser_version, observation_set_id, source_asset_id, source_root_id
                ) VALUES (
                    :id, :workId, 'WORK', 'title', 'Source',
                    'fixture', '1', :setId, :assetId, :rootId
                )
                """)
                .param("id", UUID.randomUUID())
                .param("workId", sourceWork)
                .param("setId", observationSet)
                .param("assetId", sourceAsset)
                .param("rootId", root)
                .update();
        return new Fixture(actor, reader, root, survivorWork, survivorEdition, sourceWork, sourceEdition, sourceAsset);
    }

    private CatalogRecovery.MergeOperation mergeWork(Fixture fixture, String reason) {
        var preview = recovery.preview(CatalogRecovery.SubjectType.WORK, fixture.survivorWork(), fixture.sourceWork());
        return recovery.merge(
                CatalogRecovery.SubjectType.WORK,
                fixture.survivorWork(),
                fixture.sourceWork(),
                preview.survivor().version(),
                preview.source().version(),
                UUID.randomUUID(),
                reason,
                fixture.actor());
    }

    private void seedWork(UUID root, UUID work, UUID edition, UUID asset, String title, String path) {
        jdbc.sql("INSERT INTO work (id, provisional_title, content_kind) VALUES (:id, :title, 'BOOK')")
                .param("id", work)
                .param("title", title)
                .update();
        jdbc.sql("INSERT INTO edition (id, work_id) VALUES (:id, :workId)")
                .param("id", edition)
                .param("workId", work)
                .update();
        jdbc.sql("""
                INSERT INTO asset (id, edition_id, format, byte_size, derivation, extraction_version)
                VALUES (:id, :editionId, 'EPUB', 10, 'ORIGINAL', 'recovery-test')
                """).param("id", asset).param("editionId", edition).update();
        jdbc.sql("""
                INSERT INTO asset_location (
                    id, asset_id, library_root_id, normalized_relative_path,
                    byte_size, modified_at, availability
                ) VALUES (:id, :assetId, :rootId, :path, 10, CURRENT_TIMESTAMP, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("assetId", asset)
                .param("rootId", root)
                .param("path", UUID.randomUUID() + "-" + path)
                .update();
    }

    private void seedUser(UUID id, String username, boolean owner) {
        jdbc.sql("""
                INSERT INTO user_account (
                    id, username, normalized_username, password_hash, owner, enabled
                ) VALUES (:id, :username, :username, '{noop}test-password', :owner, TRUE)
                """)
                .param("id", id)
                .param("username", username)
                .param("owner", owner)
                .update();
    }

    private UUID uuid(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(UUID.class).single();
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private record Fixture(
            UUID actor,
            UUID reader,
            UUID root,
            UUID survivorWork,
            UUID survivorEdition,
            UUID sourceWork,
            UUID sourceEdition,
            UUID sourceAsset) {}
}
