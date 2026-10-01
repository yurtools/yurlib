package org.yurlib.server.library.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.IngestionTask;
import org.yurlib.server.library.domain.ScanJob;

public final class IngestionTaskWorker {

    private static final String EXECUTION_FAILED = "INGESTION_TASK_FAILED";
    private static final String LEASE_EXPIRED = "LEASE_EXPIRED";

    private final LibraryRootStore roots;
    private final ScanJobStore jobs;
    private final IngestionTaskStore tasks;
    private final CatalogCandidateReconciler candidateReconciler;
    private final MissingLocationReconciler missingLocationReconciler;
    private final ContainedFileResolver fileResolver;
    private final IngestionResourceGovernor governor;
    private final ScanJobTelemetry telemetry;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int maximumAttempts;
    private final int fairRootShare;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The composition root owns these application collaborators for the worker lifetime.")
    public IngestionTaskWorker(
            LibraryRootStore roots,
            ScanJobStore jobs,
            IngestionTaskStore tasks,
            CatalogCandidateReconciler candidateReconciler,
            MissingLocationReconciler missingLocationReconciler,
            ContainedFileResolver fileResolver,
            IngestionResourceGovernor governor,
            ScanJobTelemetry telemetry,
            Clock clock,
            Duration leaseDuration,
            int maximumAttempts,
            int fairRootShare) {
        this.roots = roots;
        this.jobs = jobs;
        this.tasks = tasks;
        this.candidateReconciler = candidateReconciler;
        this.missingLocationReconciler = missingLocationReconciler;
        this.fileResolver = fileResolver;
        this.governor = governor;
        this.telemetry = telemetry;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        if (maximumAttempts <= 0 || maximumAttempts > 3 || fairRootShare <= 0) {
            throw new IllegalArgumentException("Attempts must be between 1 and 3; fair root share must be positive.");
        }
        this.maximumAttempts = maximumAttempts;
        this.fairRootShare = fairRootShare;
    }

    @SuppressWarnings("try")
    public boolean runNext() {
        var claimedAt = clock.instant();
        var claimed = tasks.claimNext(claimedAt, leaseDuration, maximumAttempts, fairRootShare);
        if (claimed.isEmpty()) {
            return false;
        }

        var task = claimed.get();
        if (task.exhausted()) {
            recordFailure(task, LEASE_EXPIRED, "The task lease expired too many times.");
            completeJobIfReady(task.scanJobId(), clock.instant());
            return true;
        }

        if (jobs.isCancellationRequested(task.scanJobId())) {
            tasks.complete(task.id(), task.leaseToken(), clock.instant());
            completeJobIfReady(task.scanJobId(), clock.instant());
            return true;
        }

        try (var ignored = governor.acquire(task.memoryReservationMib())) {
            var heartbeatAt = clock.instant();
            tasks.heartbeat(task.id(), task.leaseToken(), heartbeatAt, heartbeatAt.plus(leaseDuration));
            var root = roots.findById(task.libraryRootId()).orElseThrow();
            var containedFile = fileResolver.resolveContainedFile(root, task.normalizedRelativePath());
            var candidate = new ScanDiscovery.Candidate(
                    task.normalizedRelativePath(), containedFile, task.byteSize(), task.modifiedAt(), task.fileKey());
            var result = candidateReconciler.reconcile(
                    task.libraryRootId(), task.scanJobId(), job(task).extractionVersion(), candidate);
            var completedAt = clock.instant();
            tasks.heartbeat(task.id(), task.leaseToken(), completedAt, completedAt.plus(leaseDuration));
            jobs.recordOutcome(new FileOutcome(
                    task.scanJobId(),
                    task.normalizedRelativePath(),
                    FileOutcome.State.valueOf(result.state().name()),
                    result.errorCode(),
                    result.safeDiagnostic(),
                    task.attemptCount(),
                    completedAt));
            if (result.state() == CandidateReconciliationResult.State.FAILED) {
                telemetry.fileFailed(result.errorCode());
            }
            tasks.complete(task.id(), task.leaseToken(), completedAt);
            completeJobIfReady(task.scanJobId(), completedAt);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            failTask(task, "TASK_INTERRUPTED");
        } catch (RuntimeException failure) {
            failTask(task, safeCode(failure));
        }
        return true;
    }

    private ScanJob job(IngestionTask task) {
        return jobs.findById(task.scanJobId()).orElseThrow();
    }

    private void failTask(IngestionTask task, String code) {
        var failedAt = clock.instant();
        var retryable = task.attemptCount() < maximumAttempts && !jobs.isCancellationRequested(task.scanJobId());
        tasks.fail(task.id(), task.leaseToken(), code, retryable, failedAt);
        if (!retryable) {
            recordFailure(task, code, "The ingestion task could not be completed safely.");
            completeJobIfReady(task.scanJobId(), failedAt);
        }
    }

    private void recordFailure(IngestionTask task, String code, String diagnostic) {
        telemetry.fileFailed(code);
        jobs.recordOutcome(new FileOutcome(
                task.scanJobId(),
                task.normalizedRelativePath(),
                FileOutcome.State.FAILED,
                code,
                diagnostic,
                Math.max(1, task.attemptCount()),
                clock.instant()));
    }

    private void completeJobIfReady(java.util.UUID jobId, java.time.Instant completedAt) {
        jobs.completeIfReady(jobId, completedAt).ifPresent(completed -> {
            if (completed.completionCoverage() && completed.state() != ScanJob.State.CANCELLED) {
                missingLocationReconciler.reconcileAfterCompleteScan(completed.libraryRootId(), completed.id());
            }
            var startedAt = completed.startedAt() == null ? completedAt : completed.startedAt();
            telemetry.completed(completed, Duration.between(startedAt, completedAt));
        });
    }

    private static String safeCode(RuntimeException failure) {
        if (failure instanceof LibraryRootFailure rootFailure) {
            return rootFailure.code().name();
        }
        return EXECUTION_FAILED;
    }
}
