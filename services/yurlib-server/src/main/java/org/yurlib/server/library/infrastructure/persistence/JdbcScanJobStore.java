package org.yurlib.server.library.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.ScanJobFailure;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.ScanJob;

@Repository
public class JdbcScanJobStore implements ScanJobStore {

    private final JdbcClient jdbc;

    public JdbcScanJobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ScanJob queue(UUID rootId, String correlationId, String extractionVersion, Instant createdAt) {
        var jobId = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO scan_job (
                        id, library_root_id, state, correlation_id, extraction_version, created_at
                    ) VALUES (
                        :id, :rootId, 'QUEUED', :correlationId, :extractionVersion, :createdAt
                    )
                    """)
                    .param("id", jobId)
                    .param("rootId", rootId)
                    .param("correlationId", correlationId)
                    .param("extractionVersion", extractionVersion)
                    .param("createdAt", timestamp(createdAt))
                    .update();
        } catch (DataIntegrityViolationException failure) {
            if (!containsConstraint(failure, "one_active_scan_per_root")) {
                throw failure;
            }
            throw new ScanJobFailure(
                    ScanJobFailure.Code.SCAN_ALREADY_ACTIVE,
                    "A scan is already queued or running for this library root.",
                    failure);
        }
        return requireJob(jobId);
    }

    @Override
    public Optional<ScanJob> findById(UUID jobId) {
        return jdbc.sql("""
                SELECT id, library_root_id, state, correlation_id, extraction_version,
                       created_at, started_at, heartbeat_at, completed_at,
                       discovered_count, processed_count, skipped_count, failed_count,
                       completion_coverage, error_summary
                FROM scan_job
                WHERE id = :id
                """).param("id", jobId).query(ScanJob.class).optional();
    }

    @Override
    public List<FileOutcome> findFailures(UUID jobId, int limit) {
        return jdbc.sql("""
                SELECT scan_job_id, normalized_relative_path, state, error_code,
                       safe_diagnostic, attempt_count, updated_at
                FROM file_outcome
                WHERE scan_job_id = :jobId AND state = 'FAILED'
                ORDER BY normalized_relative_path
                LIMIT :limit
                """)
                .param("jobId", jobId)
                .param("limit", limit)
                .query(FileOutcome.class)
                .list();
    }

    @Override
    @Transactional
    public Optional<ScanJob> claimNext(Instant claimedAt, Instant leaseExpiredBefore) {
        var jobId = jdbc.sql("""
                SELECT id
                FROM scan_job
                WHERE cancellation_requested = FALSE
                  AND (state = 'QUEUED'
                       OR (state = 'RUNNING' AND discovery_completed = FALSE
                           AND heartbeat_at < :leaseExpiredBefore))
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """)
                .param("leaseExpiredBefore", timestamp(leaseExpiredBefore))
                .query(UUID.class)
                .optional();
        if (jobId.isEmpty()) {
            return Optional.empty();
        }
        jdbc.sql("""
                UPDATE scan_job
                SET state = 'RUNNING',
                    started_at = COALESCE(started_at, :claimedAt),
                    heartbeat_at = :claimedAt,
                    completed_at = NULL,
                    error_summary = NULL
                WHERE id = :id
                """)
                .param("claimedAt", timestamp(claimedAt))
                .param("id", jobId.get())
                .update();
        return findById(jobId.get());
    }

    @Override
    public void finishDiscovery(UUID jobId, boolean coverageComplete, Instant completedAt) {
        jdbc.sql("""
                UPDATE scan_job
                SET discovery_completed = TRUE,
                    completion_coverage = :coverageComplete,
                    heartbeat_at = :completedAt
                WHERE id = :id AND state = 'RUNNING'
                """)
                .param("coverageComplete", coverageComplete)
                .param("completedAt", timestamp(completedAt))
                .param("id", jobId)
                .update();
    }

    @Override
    public boolean isCancellationRequested(UUID jobId) {
        return jdbc.sql("SELECT cancellation_requested FROM scan_job WHERE id = :id")
                .param("id", jobId)
                .query(Boolean.class)
                .optional()
                .orElse(true);
    }

    @Override
    public ScanJob cancel(UUID jobId, Instant cancelledAt) {
        jdbc.sql("""
                UPDATE scan_job
                SET cancellation_requested = TRUE,
                    state = CASE WHEN state = 'QUEUED' THEN 'CANCELLED' ELSE state END,
                    discovery_completed = CASE WHEN state = 'QUEUED' THEN TRUE ELSE discovery_completed END,
                    completion_coverage = FALSE,
                    completed_at = CASE WHEN state = 'QUEUED' THEN :cancelledAt ELSE completed_at END,
                    heartbeat_at = :cancelledAt
                WHERE id = :id
                  AND state IN ('QUEUED', 'RUNNING')
                """)
                .param("cancelledAt", timestamp(cancelledAt))
                .param("id", jobId)
                .update();
        return requireJob(jobId);
    }

    @Override
    @Transactional
    public Optional<ScanJob> completeIfReady(UUID jobId, Instant completedAt) {
        var ready =
                jdbc.sql("""
                SELECT discovery_completed
                   AND NOT EXISTS (
                       SELECT 1 FROM ingestion_task
                       WHERE scan_job_id = scan_job.id AND state IN ('QUEUED', 'RUNNING')
                   )
                FROM scan_job
                WHERE id = :id AND state = 'RUNNING'
                FOR UPDATE
                """).param("id", jobId).query(Boolean.class).optional().orElse(false);
        if (!ready) {
            return Optional.empty();
        }
        jdbc.sql("""
                UPDATE scan_job
                SET state = CASE
                        WHEN cancellation_requested THEN 'CANCELLED'
                        WHEN failed_count = 0 AND completion_coverage THEN 'SUCCEEDED'
                        ELSE 'COMPLETED_WITH_FAILURES'
                    END,
                    heartbeat_at = :completedAt,
                    completed_at = :completedAt
                WHERE id = :id AND state = 'RUNNING'
                """)
                .param("completedAt", timestamp(completedAt))
                .param("id", jobId)
                .update();
        return findById(jobId);
    }

    @Override
    public long queuedCount() {
        return jdbc.sql("SELECT COUNT(*) FROM scan_job WHERE state = 'QUEUED'")
                .query(Long.class)
                .single();
    }

    @Override
    @Transactional
    public void recordOutcome(FileOutcome outcome) {
        var previousState = jdbc.sql("""
                SELECT state
                FROM file_outcome
                WHERE scan_job_id = :jobId AND normalized_relative_path = :path
                FOR UPDATE
                """)
                .param("jobId", outcome.scanJobId())
                .param("path", outcome.normalizedRelativePath())
                .query(String.class)
                .optional();

        if (previousState.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO file_outcome (
                        scan_job_id, normalized_relative_path, state, error_code,
                        safe_diagnostic, attempt_count, updated_at
                    ) VALUES (
                        :jobId, :path, :state, :errorCode, :safeDiagnostic, :attemptCount, :updatedAt
                    )
                    """)
                    .param("jobId", outcome.scanJobId())
                    .param("path", outcome.normalizedRelativePath())
                    .param("state", outcome.state().name())
                    .param("errorCode", outcome.errorCode())
                    .param("safeDiagnostic", outcome.safeDiagnostic())
                    .param("attemptCount", outcome.attemptCount())
                    .param("updatedAt", timestamp(outcome.updatedAt()))
                    .update();
        } else {
            jdbc.sql("""
                    UPDATE file_outcome
                    SET state = :state,
                        error_code = :errorCode,
                        safe_diagnostic = :safeDiagnostic,
                        attempt_count = attempt_count + 1,
                        updated_at = :updatedAt
                    WHERE scan_job_id = :jobId AND normalized_relative_path = :path
                    """)
                    .param("jobId", outcome.scanJobId())
                    .param("path", outcome.normalizedRelativePath())
                    .param("state", outcome.state().name())
                    .param("errorCode", outcome.errorCode())
                    .param("safeDiagnostic", outcome.safeDiagnostic())
                    .param("updatedAt", timestamp(outcome.updatedAt()))
                    .update();
        }

        var previous = previousState.map(FileOutcome.State::valueOf).orElse(null);
        updateCounters(outcome.scanJobId(), previous, outcome.state(), previousState.isEmpty());
    }

    @Override
    public void heartbeat(UUID jobId, Instant heartbeatAt) {
        jdbc.sql("""
                UPDATE scan_job
                SET heartbeat_at = :heartbeatAt
                WHERE id = :id AND state = 'RUNNING'
                """)
                .param("heartbeatAt", timestamp(heartbeatAt))
                .param("id", jobId)
                .update();
    }

    @Override
    public ScanJob complete(UUID jobId, boolean coverageComplete, Instant completedAt) {
        jdbc.sql("""
                UPDATE scan_job
                SET state = CASE
                        WHEN failed_count = 0 AND :coverageComplete THEN 'SUCCEEDED'
                        ELSE 'COMPLETED_WITH_FAILURES'
                    END,
                    heartbeat_at = :completedAt,
                    completed_at = :completedAt,
                    completion_coverage = :coverageComplete
                WHERE id = :id AND state = 'RUNNING'
                """)
                .param("coverageComplete", coverageComplete)
                .param("completedAt", timestamp(completedAt))
                .param("id", jobId)
                .update();
        return requireJob(jobId);
    }

    @Override
    public ScanJob fail(UUID jobId, String safeSummary, Instant completedAt) {
        jdbc.sql("""
                UPDATE scan_job
                SET state = 'FAILED',
                    heartbeat_at = :completedAt,
                    completed_at = :completedAt,
                    completion_coverage = FALSE,
                    error_summary = :safeSummary
                WHERE id = :id AND state = 'RUNNING'
                """)
                .param("safeSummary", safeSummary)
                .param("completedAt", timestamp(completedAt))
                .param("id", jobId)
                .update();
        return requireJob(jobId);
    }

    private void updateCounters(
            UUID jobId, FileOutcome.State previous, FileOutcome.State current, boolean newlyDiscovered) {
        jdbc.sql("""
                UPDATE scan_job
                SET discovered_count = discovered_count + :discoveredDelta,
                    processed_count = processed_count + :processedDelta,
                    skipped_count = skipped_count + :skippedDelta,
                    failed_count = failed_count + :failedDelta
                WHERE id = :jobId AND state = 'RUNNING'
                """)
                .param("discoveredDelta", newlyDiscovered ? 1 : 0)
                .param(
                        "processedDelta",
                        contribution(current, FileOutcome.State.PROCESSED)
                                - contribution(previous, FileOutcome.State.PROCESSED))
                .param(
                        "skippedDelta",
                        contribution(current, FileOutcome.State.SKIPPED)
                                - contribution(previous, FileOutcome.State.SKIPPED))
                .param(
                        "failedDelta",
                        contribution(current, FileOutcome.State.FAILED)
                                - contribution(previous, FileOutcome.State.FAILED))
                .param("jobId", jobId)
                .update();
    }

    private static int contribution(FileOutcome.State actual, FileOutcome.State expected) {
        return actual == expected ? 1 : 0;
    }

    private ScanJob requireJob(UUID jobId) {
        return findById(jobId)
                .orElseThrow(() -> new ScanJobFailure(
                        ScanJobFailure.Code.JOB_NOT_FOUND, "The requested scan job does not exist."));
    }

    private static boolean containsConstraint(Throwable failure, String constraint) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
