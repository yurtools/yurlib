package org.yurlib.server.library.application;

import java.time.Clock;
import java.util.UUID;
import org.yurlib.server.library.domain.ScanJob;

public final class DefaultScanJobService implements ScanJobUseCases {

    static final String EXTRACTION_VERSION = "discovery-v1";
    private static final int MAXIMUM_RETURNED_FAILURES = 100;

    private final LibraryRootStore roots;
    private final ScanJobStore jobs;
    private final Clock clock;

    public DefaultScanJobService(LibraryRootStore roots, ScanJobStore jobs, Clock clock) {
        this.roots = roots;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Override
    public ScanJob queue(UUID rootId, String correlationId) {
        roots.findById(rootId)
                .orElseThrow(() -> new ScanJobFailure(
                        ScanJobFailure.Code.ROOT_NOT_FOUND, "The requested library root does not exist."));
        return jobs.queue(rootId, correlationId, EXTRACTION_VERSION, clock.instant());
    }

    @Override
    public ScanJobView get(UUID jobId) {
        var job = jobs.findById(jobId)
                .orElseThrow(() -> new ScanJobFailure(
                        ScanJobFailure.Code.JOB_NOT_FOUND, "The requested scan job does not exist."));
        return new ScanJobView(job, jobs.findFailures(jobId, MAXIMUM_RETURNED_FAILURES));
    }
}
