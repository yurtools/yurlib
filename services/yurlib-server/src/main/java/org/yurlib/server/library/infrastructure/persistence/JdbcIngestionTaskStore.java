package org.yurlib.server.library.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.IngestionTaskStore;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.domain.IngestionTask;

@Repository
public class JdbcIngestionTaskStore implements IngestionTaskStore {

    private static final long QUEUE_ADVISORY_LOCK = 9_347_561L;

    private final JdbcClient jdbc;

    public JdbcIngestionTaskStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean enqueue(
            UUID rootId,
            UUID scanJobId,
            String extractionVersion,
            ScanDiscovery.Candidate candidate,
            int queueCapacity,
            Instant createdAt) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(:lockId)")
                .param("lockId", QUEUE_ADVISORY_LOCK)
                .query(Long.class)
                .single();
        var activeCount = jdbc.sql("""
                SELECT COUNT(*)
                FROM ingestion_task
                WHERE state IN ('QUEUED', 'RUNNING')
                """).query(Long.class).single();
        if (activeCount >= queueCapacity) {
            return false;
        }

        jdbc.sql("""
                INSERT INTO ingestion_root_schedule (library_root_id)
                VALUES (:rootId)
                ON CONFLICT (library_root_id) DO NOTHING
                """).param("rootId", rootId).update();
        var inserted = jdbc.sql("""
                INSERT INTO ingestion_task (
                    id, scan_job_id, library_root_id, stage, idempotency_key,
                    normalized_relative_path, byte_size, modified_at, file_key,
                    memory_reservation_mib, state, created_at
                ) VALUES (
                    :id, :scanJobId, :rootId, 'METADATA', :idempotencyKey,
                    :path, :byteSize, :modifiedAt, :fileKey,
                    :memoryReservationMib, 'QUEUED', :createdAt
                )
                ON CONFLICT (scan_job_id, idempotency_key) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("scanJobId", scanJobId)
                .param("rootId", rootId)
                .param("idempotencyKey", idempotencyKey(scanJobId, extractionVersion, candidate))
                .param("path", candidate.normalizedRelativePath())
                .param("byteSize", candidate.byteSize())
                .param("modifiedAt", timestamp(candidate.modifiedAt()))
                .param("fileKey", candidate.fileKey())
                .param("memoryReservationMib", memoryReservation(candidate.normalizedRelativePath()))
                .param("createdAt", timestamp(createdAt))
                .update();
        return inserted > 0 || taskExists(scanJobId, candidate.normalizedRelativePath());
    }

    @Override
    @Transactional
    public Optional<IngestionTask> claimNext(
            Instant claimedAt, Duration leaseDuration, int maximumAttempts, int fairRootShare) {
        var rootId = jdbc.sql("""
                SELECT schedule.library_root_id
                FROM ingestion_root_schedule schedule
                WHERE EXISTS (
                    SELECT 1
                    FROM ingestion_task candidate
                    JOIN scan_job job ON job.id = candidate.scan_job_id
                    WHERE candidate.library_root_id = schedule.library_root_id
                      AND job.cancellation_requested = FALSE
                      AND job.state = 'RUNNING'
                      AND (
                          candidate.state = 'QUEUED'
                          OR (candidate.state = 'RUNNING' AND candidate.lease_expires_at < :claimedAt)
                      )
                )
                  AND (
                    (SELECT COUNT(DISTINCT queued.library_root_id)
                     FROM ingestion_task queued
                     JOIN scan_job queued_job ON queued_job.id = queued.scan_job_id
                     WHERE queued_job.cancellation_requested = FALSE
                       AND queued_job.state = 'RUNNING'
                       AND (queued.state = 'QUEUED'
                            OR (queued.state = 'RUNNING' AND queued.lease_expires_at < :claimedAt))) <= 1
                    OR (SELECT COUNT(*)
                        FROM ingestion_task running
                        WHERE running.library_root_id = schedule.library_root_id
                          AND running.state = 'RUNNING'
                          AND running.lease_expires_at >= :claimedAt) < :fairRootShare
                  )
                ORDER BY schedule.last_claimed_at NULLS FIRST,
                         schedule.claim_sequence,
                         schedule.library_root_id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """)
                .param("claimedAt", timestamp(claimedAt))
                .param("fairRootShare", fairRootShare)
                .query(UUID.class)
                .optional();
        if (rootId.isEmpty()) {
            return Optional.empty();
        }

        var row = jdbc.sql("""
                SELECT task.id, task.scan_job_id, task.library_root_id, task.idempotency_key,
                       task.normalized_relative_path, task.byte_size, task.modified_at, task.file_key,
                       task.memory_reservation_mib, task.attempt_count
                FROM ingestion_task task
                JOIN scan_job job ON job.id = task.scan_job_id
                WHERE task.library_root_id = :rootId
                  AND job.state = 'RUNNING'
                  AND job.cancellation_requested = FALSE
                  AND (task.state = 'QUEUED'
                       OR (task.state = 'RUNNING' AND task.lease_expires_at < :claimedAt))
                ORDER BY task.created_at, task.id
                FOR UPDATE OF task SKIP LOCKED
                LIMIT 1
                """)
                .param("rootId", rootId.get())
                .param("claimedAt", timestamp(claimedAt))
                .query(JdbcIngestionTaskStore::mapRow)
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }

        jdbc.sql("""
                UPDATE ingestion_root_schedule
                SET last_claimed_at = :claimedAt,
                    claim_sequence = claim_sequence + 1
                WHERE library_root_id = :rootId
                """)
                .param("claimedAt", timestamp(claimedAt))
                .param("rootId", rootId.get())
                .update();

        var task = row.get();
        if (task.attemptCount() >= maximumAttempts) {
            jdbc.sql("""
                    UPDATE ingestion_task
                    SET state = 'FAILED', lease_token = NULL, lease_expires_at = NULL,
                        error_code = 'LEASE_EXPIRED', completed_at = :claimedAt
                    WHERE id = :id
                    """)
                    .param("claimedAt", timestamp(claimedAt))
                    .param("id", task.id())
                    .update();
            return Optional.of(task.exhausted());
        }

        var leaseToken = UUID.randomUUID();
        var leaseExpiresAt = claimedAt.plus(leaseDuration);
        jdbc.sql("""
                UPDATE ingestion_task
                SET state = 'RUNNING', attempt_count = attempt_count + 1,
                    lease_token = :leaseToken, lease_expires_at = :leaseExpiresAt,
                    heartbeat_at = :claimedAt, error_code = NULL, completed_at = NULL
                WHERE id = :id
                """)
                .param("leaseToken", leaseToken)
                .param("leaseExpiresAt", timestamp(leaseExpiresAt))
                .param("claimedAt", timestamp(claimedAt))
                .param("id", task.id())
                .update();
        return Optional.of(task.claimed(leaseToken, leaseExpiresAt));
    }

    @Override
    public void heartbeat(UUID taskId, UUID leaseToken, Instant heartbeatAt, Instant leaseExpiresAt) {
        jdbc.sql("""
                UPDATE ingestion_task
                SET heartbeat_at = :heartbeatAt, lease_expires_at = :leaseExpiresAt
                WHERE id = :id AND state = 'RUNNING' AND lease_token = :leaseToken
                """)
                .param("heartbeatAt", timestamp(heartbeatAt))
                .param("leaseExpiresAt", timestamp(leaseExpiresAt))
                .param("id", taskId)
                .param("leaseToken", leaseToken)
                .update();
    }

    @Override
    public void complete(UUID taskId, UUID leaseToken, Instant completedAt) {
        transition(taskId, leaseToken, "SUCCEEDED", null, false, completedAt);
    }

    @Override
    public void fail(UUID taskId, UUID leaseToken, String errorCode, boolean retryable, Instant failedAt) {
        transition(taskId, leaseToken, retryable ? "QUEUED" : "FAILED", errorCode, retryable, failedAt);
    }

    @Override
    public void cancelQueued(UUID scanJobId, Instant cancelledAt) {
        jdbc.sql("""
                UPDATE ingestion_task
                SET state = 'CANCELLED', completed_at = :cancelledAt
                WHERE scan_job_id = :scanJobId AND state = 'QUEUED'
                """)
                .param("cancelledAt", timestamp(cancelledAt))
                .param("scanJobId", scanJobId)
                .update();
    }

    @Override
    public long queuedCount() {
        return count("QUEUED");
    }

    @Override
    public long runningCount() {
        return count("RUNNING");
    }

    private void transition(
            UUID taskId, UUID leaseToken, String state, String errorCode, boolean retryable, Instant completedAt) {
        jdbc.sql("""
                UPDATE ingestion_task
                SET state = :state, lease_token = NULL, lease_expires_at = NULL,
                    heartbeat_at = :completedAt, error_code = :errorCode,
                    completed_at = CASE
                        WHEN :retryable THEN NULL::TIMESTAMPTZ
                        ELSE CAST(:completedAt AS TIMESTAMPTZ)
                    END
                WHERE id = :id AND state = 'RUNNING' AND lease_token = :leaseToken
                """)
                .param("state", state)
                .param("errorCode", errorCode)
                .param("retryable", retryable)
                .param("completedAt", timestamp(completedAt))
                .param("id", taskId)
                .param("leaseToken", leaseToken)
                .update();
    }

    private long count(String state) {
        return jdbc.sql("SELECT COUNT(*) FROM ingestion_task WHERE state = :state")
                .param("state", state)
                .query(Long.class)
                .single();
    }

    private boolean taskExists(UUID scanJobId, String path) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM ingestion_task
                    WHERE scan_job_id = :scanJobId AND normalized_relative_path = :path
                )
                """)
                .param("scanJobId", scanJobId)
                .param("path", path)
                .query(Boolean.class)
                .single();
    }

    private static TaskRow mapRow(ResultSet result, int ignoredRowNumber) throws SQLException {
        return new TaskRow(
                result.getObject("id", UUID.class),
                result.getObject("scan_job_id", UUID.class),
                result.getObject("library_root_id", UUID.class),
                result.getString("idempotency_key"),
                result.getString("normalized_relative_path"),
                result.getLong("byte_size"),
                result.getTimestamp("modified_at").toInstant(),
                result.getString("file_key"),
                result.getInt("memory_reservation_mib"),
                result.getInt("attempt_count"));
    }

    private static int memoryReservation(String path) {
        var lowercase = path.toLowerCase(Locale.ROOT);
        return lowercase.endsWith(".epub") || lowercase.endsWith(".docx") ? 128 : 64;
    }

    private static String idempotencyKey(UUID scanJobId, String extractionVersion, ScanDiscovery.Candidate candidate) {
        var value = String.join(
                "\n",
                scanJobId.toString(),
                extractionVersion,
                candidate.normalizedRelativePath(),
                Long.toString(candidate.byteSize()),
                candidate.modifiedAt().toString(),
                candidate.fileKey() == null ? "" : candidate.fileKey());
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", failure);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record TaskRow(
            UUID id,
            UUID scanJobId,
            UUID rootId,
            String idempotencyKey,
            String path,
            long byteSize,
            Instant modifiedAt,
            String fileKey,
            int memoryReservationMib,
            int attemptCount) {

        IngestionTask claimed(UUID leaseToken, Instant leaseExpiresAt) {
            return task(attemptCount + 1, leaseToken, leaseExpiresAt, false);
        }

        IngestionTask exhausted() {
            return task(attemptCount, null, null, true);
        }

        private IngestionTask task(int attempts, UUID leaseToken, Instant leaseExpiresAt, boolean exhausted) {
            return new IngestionTask(
                    id,
                    scanJobId,
                    rootId,
                    idempotencyKey,
                    path,
                    byteSize,
                    modifiedAt,
                    fileKey,
                    memoryReservationMib,
                    attempts,
                    leaseToken,
                    leaseExpiresAt,
                    exhausted);
        }
    }
}
