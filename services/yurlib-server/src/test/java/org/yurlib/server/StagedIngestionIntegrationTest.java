package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.IngestionTaskStore;
import org.yurlib.server.library.application.ScanDiscovery;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class StagedIngestionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final UUID FIRST_ROOT = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SECOND_ROOT = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private IngestionTaskStore tasks;

    private JdbcClient jdbc;

    @Autowired
    void jdbc(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM ingestion_task").update();
        jdbc.sql("DELETE FROM ingestion_root_schedule").update();
        jdbc.sql("DELETE FROM scan_job").update();
        jdbc.sql("DELETE FROM library_root").update();
        insertRoot(FIRST_ROOT, "first");
        insertRoot(SECOND_ROOT, "second");
    }

    @Test
    void enforcesQueueCapacityAndIdempotentEnqueue() {
        var job = insertJob(FIRST_ROOT);
        var candidate = candidate("one.epub");

        assertThat(tasks.enqueue(FIRST_ROOT, job, "extract-v1", candidate, 1, NOW))
                .isTrue();
        assertThat(tasks.enqueue(FIRST_ROOT, job, "extract-v1", candidate, 1, NOW))
                .isFalse();
        assertThat(tasks.enqueue(FIRST_ROOT, job, "extract-v1", candidate("two.epub"), 1, NOW))
                .isFalse();
        assertThat(tasks.queuedCount()).isEqualTo(1);
    }

    @Test
    void alternatesRootsWhileBothHaveQueuedWork() {
        var firstJob = insertJob(FIRST_ROOT);
        var secondJob = insertJob(SECOND_ROOT);
        assertThat(tasks.enqueue(FIRST_ROOT, firstJob, "extract-v1", candidate("first.epub"), 256, NOW))
                .isTrue();
        assertThat(tasks.enqueue(SECOND_ROOT, secondJob, "extract-v1", candidate("second.epub"), 256, NOW))
                .isTrue();

        var firstClaim = tasks.claimNext(NOW, LEASE, 3, 1).orElseThrow();
        var secondClaim = tasks.claimNext(NOW.plusMillis(1), LEASE, 3, 1).orElseThrow();

        assertThat(firstClaim.libraryRootId()).isNotEqualTo(secondClaim.libraryRootId());
        assertThat(tasks.runningCount()).isEqualTo(2);
    }

    @Test
    void leaseExpiryRetriesAndRejectsTheStaleLease() {
        var job = insertJob(FIRST_ROOT);
        assertThat(tasks.enqueue(FIRST_ROOT, job, "extract-v1", candidate("retry.epub"), 256, NOW))
                .isTrue();
        var first = tasks.claimNext(NOW, LEASE, 3, 1).orElseThrow();
        var second =
                tasks.claimNext(NOW.plus(LEASE).plusSeconds(1), LEASE, 3, 1).orElseThrow();

        tasks.complete(first.id(), first.leaseToken(), NOW.plusSeconds(62));
        assertThat(state(first.id())).isEqualTo("RUNNING");
        tasks.complete(second.id(), second.leaseToken(), NOW.plusSeconds(63));

        assertThat(state(first.id())).isEqualTo("SUCCEEDED");
        assertThat(attempts(first.id())).isEqualTo(2);
    }

    @Test
    void terminallyFailsAfterTheBoundedAttemptCount() {
        var job = insertJob(FIRST_ROOT);
        assertThat(tasks.enqueue(FIRST_ROOT, job, "extract-v1", candidate("exhausted.epub"), 256, NOW))
                .isTrue();
        var claim = tasks.claimNext(NOW, LEASE, 3, 1).orElseThrow();
        claim = tasks.claimNext(claim.leaseExpiresAt().plusSeconds(1), LEASE, 3, 1)
                .orElseThrow();
        claim = tasks.claimNext(claim.leaseExpiresAt().plusSeconds(1), LEASE, 3, 1)
                .orElseThrow();
        var exhausted = tasks.claimNext(claim.leaseExpiresAt().plusSeconds(1), LEASE, 3, 1)
                .orElseThrow();

        assertThat(exhausted.exhausted()).isTrue();
        assertThat(state(exhausted.id())).isEqualTo("FAILED");
        assertThat(attempts(exhausted.id())).isEqualTo(3);
    }

    @Test
    void cancellationRemovesQueuedTasksFromClaiming() {
        var job = insertJob(FIRST_ROOT);
        assertThat(tasks.enqueue(FIRST_ROOT, job, "extract-v1", candidate("cancel.epub"), 256, NOW))
                .isTrue();

        tasks.cancelQueued(job, NOW.plusSeconds(1));

        assertThat(tasks.claimNext(NOW.plusSeconds(2), LEASE, 3, 1)).isEmpty();
        assertThat(tasks.queuedCount()).isZero();
    }

    private void insertRoot(UUID id, String alias) {
        jdbc.sql("""
                INSERT INTO library_root (
                    id, name, mount_alias, relative_base_path, expected_identity_digest, mode
                ) VALUES (:id, :name, :alias, 'books', :digest, 'READ_ONLY_SOURCE')
                """)
                .param("id", id)
                .param("name", alias)
                .param("alias", alias)
                .param("digest", "a".repeat(64))
                .update();
    }

    private UUID insertJob(UUID rootId) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO scan_job (
                    id, library_root_id, state, correlation_id, extraction_version, created_at
                ) VALUES (:id, :rootId, 'RUNNING', :correlationId, 'extract-v1', :createdAt)
                """)
                .param("id", id)
                .param("rootId", rootId)
                .param("correlationId", UUID.randomUUID().toString())
                .param("createdAt", Timestamp.from(NOW))
                .update();
        return id;
    }

    private String state(UUID taskId) {
        return jdbc.sql("SELECT state FROM ingestion_task WHERE id = :id")
                .param("id", taskId)
                .query(String.class)
                .single();
    }

    private int attempts(UUID taskId) {
        return jdbc.sql("SELECT attempt_count FROM ingestion_task WHERE id = :id")
                .param("id", taskId)
                .query(Integer.class)
                .single();
    }

    private static ScanDiscovery.Candidate candidate(String path) {
        return new ScanDiscovery.Candidate(path, Path.of(path), 100, NOW, "file-key-" + path);
    }
}
