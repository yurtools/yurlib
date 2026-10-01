package org.yurlib.server.library.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.yurlib.server.library.domain.FileOutcome;

public final class ScanJobWorker {

    private final LibraryRootStore roots;
    private final ScanJobStore jobs;
    private final ScanDiscovery discovery;
    private final IngestionTaskStore tasks;
    private final MissingLocationReconciler reconciler;
    private final Clock clock;
    private final Duration leaseTimeout;
    private final ScanJobTelemetry telemetry;
    private final int queueCapacity;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The composition root owns these application ports for the worker lifetime.")
    public ScanJobWorker(
            LibraryRootStore roots,
            ScanJobStore jobs,
            ScanDiscovery discovery,
            IngestionTaskStore tasks,
            MissingLocationReconciler reconciler,
            Clock clock,
            Duration leaseTimeout,
            ScanJobTelemetry telemetry,
            int queueCapacity) {
        this.roots = roots;
        this.jobs = jobs;
        this.discovery = discovery;
        this.tasks = tasks;
        this.reconciler = reconciler;
        this.clock = clock;
        this.leaseTimeout = leaseTimeout;
        this.telemetry = telemetry;
        if (queueCapacity <= 0 || queueCapacity > 256) {
            throw new IllegalArgumentException("queueCapacity must be between 1 and 256");
        }
        this.queueCapacity = queueCapacity;
    }

    public boolean runNext() {
        var claimedAt = clock.instant();
        var claimed = jobs.claimNext(claimedAt, claimedAt.minus(leaseTimeout));
        if (claimed.isEmpty()) {
            return false;
        }

        var job = claimed.get();
        telemetry.started(job);
        try {
            var root = roots.findById(job.libraryRootId()).orElseThrow();
            var listener = new PersistingDiscoveryListener(
                    root.id(), job.id(), job.extractionVersion(), jobs, tasks, clock, telemetry, queueCapacity);
            var result = discovery.discover(root, listener);
            var completedAt = clock.instant();
            jobs.finishDiscovery(job.id(), result.coverageComplete(), completedAt);
            jobs.completeIfReady(job.id(), completedAt).ifPresent(completed -> {
                if (completed.completionCoverage()
                        && completed.state() != org.yurlib.server.library.domain.ScanJob.State.CANCELLED) {
                    reconciler.reconcileAfterCompleteScan(root.id(), job.id());
                }
                telemetry.completed(completed, Duration.between(claimedAt, completedAt));
            });
        } catch (RuntimeException failure) {
            var failedAt = clock.instant();
            var summary = safeSummary(failure);
            tasks.cancelQueued(job.id(), failedAt);
            var failed = jobs.fail(job.id(), summary, failedAt);
            telemetry.failed(failed, summary, Duration.between(claimedAt, failedAt));
        }
        return true;
    }

    private static String safeSummary(RuntimeException failure) {
        if (failure instanceof LibraryRootFailure rootFailure) {
            return rootFailure.code().name();
        }
        return "SCAN_EXECUTION_FAILED";
    }

    private static final class PersistingDiscoveryListener implements ScanDiscovery.Listener {

        private final UUID jobId;
        private final UUID rootId;
        private final String extractionVersion;
        private final ScanJobStore jobs;
        private final IngestionTaskStore tasks;
        private final Clock clock;
        private final ScanJobTelemetry telemetry;
        private final int queueCapacity;

        private PersistingDiscoveryListener(
                UUID rootId,
                UUID jobId,
                String extractionVersion,
                ScanJobStore jobs,
                IngestionTaskStore tasks,
                Clock clock,
                ScanJobTelemetry telemetry,
                int queueCapacity) {
            this.rootId = rootId;
            this.jobId = jobId;
            this.extractionVersion = extractionVersion;
            this.jobs = jobs;
            this.tasks = tasks;
            this.clock = clock;
            this.telemetry = telemetry;
            this.queueCapacity = queueCapacity;
        }

        @Override
        public boolean cancellationRequested() {
            return jobs.isCancellationRequested(jobId) || Thread.currentThread().isInterrupted();
        }

        @Override
        public void heartbeat() {
            jobs.heartbeat(jobId, clock.instant());
        }

        @Override
        public void discovered(ScanDiscovery.Candidate candidate) {
            while (!cancellationRequested()
                    && !tasks.enqueue(rootId, jobId, extractionVersion, candidate, queueCapacity, clock.instant())) {
                jobs.heartbeat(jobId, clock.instant());
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
        }

        @Override
        public void failed(String normalizedRelativePath, String code, String safeDiagnostic) {
            telemetry.fileFailed(code);
            jobs.recordOutcome(new FileOutcome(
                    jobId, normalizedRelativePath, FileOutcome.State.FAILED, code, safeDiagnostic, 1, clock.instant()));
        }
    }
}
