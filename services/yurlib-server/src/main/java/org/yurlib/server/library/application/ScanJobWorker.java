package org.yurlib.server.library.application;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.yurlib.server.library.domain.FileOutcome;

public final class ScanJobWorker {

    private final LibraryRootStore roots;
    private final ScanJobStore jobs;
    private final ScanDiscovery discovery;
    private final MissingLocationReconciler reconciler;
    private final Clock clock;
    private final Duration leaseTimeout;

    public ScanJobWorker(
            LibraryRootStore roots,
            ScanJobStore jobs,
            ScanDiscovery discovery,
            MissingLocationReconciler reconciler,
            Clock clock,
            Duration leaseTimeout) {
        this.roots = roots;
        this.jobs = jobs;
        this.discovery = discovery;
        this.reconciler = reconciler;
        this.clock = clock;
        this.leaseTimeout = leaseTimeout;
    }

    public boolean runNext() {
        var claimedAt = clock.instant();
        var claimed = jobs.claimNext(claimedAt, claimedAt.minus(leaseTimeout));
        if (claimed.isEmpty()) {
            return false;
        }

        var job = claimed.get();
        try {
            var root = roots.findById(job.libraryRootId()).orElseThrow();
            var listener = new PersistingDiscoveryListener(job.id(), jobs, clock);
            var result = discovery.discover(root, listener);
            if (result.coverageComplete()) {
                reconciler.reconcileAfterCompleteScan(root.id(), job.id());
            }
            jobs.complete(job.id(), result.coverageComplete(), clock.instant());
        } catch (RuntimeException failure) {
            jobs.fail(job.id(), safeSummary(failure), clock.instant());
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
        private final ScanJobStore jobs;
        private final Clock clock;

        private PersistingDiscoveryListener(UUID jobId, ScanJobStore jobs, Clock clock) {
            this.jobId = jobId;
            this.jobs = jobs;
            this.clock = clock;
        }

        @Override
        public void heartbeat() {
            jobs.heartbeat(jobId, clock.instant());
        }

        @Override
        public void discovered(String normalizedRelativePath) {
            jobs.recordOutcome(new FileOutcome(
                    jobId, normalizedRelativePath, FileOutcome.State.DISCOVERED, null, null, 1, clock.instant()));
        }

        @Override
        public void failed(String normalizedRelativePath, String code, String safeDiagnostic) {
            jobs.recordOutcome(new FileOutcome(
                    jobId, normalizedRelativePath, FileOutcome.State.FAILED, code, safeDiagnostic, 1, clock.instant()));
        }
    }
}
