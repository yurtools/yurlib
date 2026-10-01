package org.yurlib.server.library.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.UUID;
import org.yurlib.server.library.domain.ScanJob;

public final class DefaultScanJobService implements ScanJobUseCases {

    private static final int MAXIMUM_RETURNED_FAILURES = 100;

    private final LibraryRootStore roots;
    private final ScanJobStore jobs;
    private final LibraryRootAccess access;
    private final MetadataExtractor extractor;
    private final IngestionTaskStore tasks;
    private final Clock clock;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The composition root owns these application ports for the service lifetime.")
    public DefaultScanJobService(
            LibraryRootStore roots,
            ScanJobStore jobs,
            LibraryRootAccess access,
            MetadataExtractor extractor,
            IngestionTaskStore tasks,
            Clock clock) {
        this.roots = roots;
        this.jobs = jobs;
        this.access = access;
        this.extractor = extractor;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Override
    public ScanJob queue(UUID rootId, String correlationId) {
        var root = roots.findById(rootId)
                .orElseThrow(() -> new ScanJobFailure(
                        ScanJobFailure.Code.ROOT_NOT_FOUND, "The requested library root does not exist."));
        if (root.mode() != org.yurlib.server.library.domain.LibraryRoot.Mode.READ_ONLY_SOURCE) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_MODE_INVALID, "Managed-output roots cannot be scanned.");
        }
        if (!access.isAllowed(rootId)) {
            throw new ScanJobFailure(ScanJobFailure.Code.ROOT_NOT_FOUND, "The requested library root does not exist.");
        }
        return jobs.queue(rootId, correlationId, extractor.extractionVersion(), clock.instant());
    }

    @Override
    public ScanJobView get(UUID jobId) {
        var job = jobs.findById(jobId)
                .orElseThrow(() -> new ScanJobFailure(
                        ScanJobFailure.Code.JOB_NOT_FOUND, "The requested scan job does not exist."));
        if (!access.isAllowed(job.libraryRootId())) {
            throw new ScanJobFailure(ScanJobFailure.Code.JOB_NOT_FOUND, "The requested scan job does not exist.");
        }
        return new ScanJobView(job, jobs.findFailures(jobId, MAXIMUM_RETURNED_FAILURES));
    }

    @Override
    public ScanJobView cancel(UUID jobId) {
        var job = requireVisibleJob(jobId);
        var cancelledAt = clock.instant();
        var cancelled = jobs.cancel(job.id(), cancelledAt);
        tasks.cancelQueued(job.id(), cancelledAt);
        jobs.completeIfReady(job.id(), cancelledAt).ifPresent(ignored -> {});
        return get(cancelled.id());
    }

    private ScanJob requireVisibleJob(UUID jobId) {
        var job = jobs.findById(jobId)
                .orElseThrow(() -> new ScanJobFailure(
                        ScanJobFailure.Code.JOB_NOT_FOUND, "The requested scan job does not exist."));
        if (!access.isAllowed(job.libraryRootId())) {
            throw new ScanJobFailure(ScanJobFailure.Code.JOB_NOT_FOUND, "The requested scan job does not exist.");
        }
        return job;
    }
}
