package org.yurlib.server.library.infrastructure.observability;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.application.IngestionResourceGovernor;
import org.yurlib.server.library.application.IngestionTaskStore;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.application.ScanJobTelemetry;
import org.yurlib.server.library.domain.ScanJob;

@Component
public final class MicrometerScanJobTelemetry implements ScanJobTelemetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(MicrometerScanJobTelemetry.class);

    private final MeterRegistry registry;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "Spring owns the shared MeterRegistry; telemetry must register and update application meters.")
    public MicrometerScanJobTelemetry(
            MeterRegistry registry, ScanJobStore jobs, IngestionTaskStore tasks, IngestionResourceGovernor governor) {
        this.registry = registry;
        Gauge.builder("yurlib.scan.queue.depth", jobs, ScanJobStore::queuedCount)
                .description("Number of scan jobs waiting to run")
                .register(registry);
        Gauge.builder("yurlib.ingestion.tasks", tasks, IngestionTaskStore::queuedCount)
                .description("Number of staged ingestion tasks waiting to run")
                .tag("state", "queued")
                .register(registry);
        Gauge.builder("yurlib.ingestion.tasks", tasks, IngestionTaskStore::runningCount)
                .description("Number of staged ingestion tasks currently leased")
                .tag("state", "running")
                .register(registry);
        Gauge.builder(
                        "yurlib.ingestion.resources.available.memory.mib",
                        governor,
                        value -> value.snapshot().availableMemoryMib())
                .description("Unreserved ingestion work-memory permits")
                .register(registry);
        Gauge.builder(
                        "yurlib.ingestion.resources.available.open.files",
                        governor,
                        value -> value.snapshot().availableOpenFiles())
                .description("Available ingestion open-file permits")
                .register(registry);
    }

    @Override
    public void started(ScanJob job) {
        LOGGER.atInfo()
                .addKeyValue("correlationId", job.correlationId())
                .addKeyValue("scanJobId", job.id())
                .addKeyValue("libraryRootId", job.libraryRootId())
                .log("Scan job started");
    }

    @Override
    public void fileFailed(String code) {
        Counter.builder("yurlib.scan.parser.failures")
                .description("Files that could not be safely cataloged")
                .tag("code", safeCode(code))
                .register(registry)
                .increment();
    }

    @Override
    public void completed(ScanJob job, Duration duration) {
        record(job, duration);
        LOGGER.atInfo()
                .addKeyValue("correlationId", job.correlationId())
                .addKeyValue("scanJobId", job.id())
                .addKeyValue("libraryRootId", job.libraryRootId())
                .addKeyValue("state", job.state())
                .addKeyValue("durationMs", duration.toMillis())
                .addKeyValue("discoveredCount", job.discoveredCount())
                .addKeyValue("processedCount", job.processedCount())
                .addKeyValue("skippedCount", job.skippedCount())
                .addKeyValue("failedCount", job.failedCount())
                .log("Scan job completed");
    }

    @Override
    public void failed(ScanJob job, String code, Duration duration) {
        record(job, duration);
        LOGGER.atWarn()
                .addKeyValue("correlationId", job.correlationId())
                .addKeyValue("scanJobId", job.id())
                .addKeyValue("libraryRootId", job.libraryRootId())
                .addKeyValue("state", job.state())
                .addKeyValue("durationMs", duration.toMillis())
                .addKeyValue("code", safeCode(code))
                .log("Scan job failed");
    }

    private void record(ScanJob job, Duration duration) {
        var outcome = job.state().name();
        Counter.builder("yurlib.scan.jobs")
                .description("Completed scan jobs")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("yurlib.scan.duration")
                .description("Scan job execution duration")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
        incrementFiles("processed", job.processedCount());
        incrementFiles("skipped", job.skippedCount());
        incrementFiles("failed", job.failedCount());
    }

    private void incrementFiles(String outcome, long count) {
        registry.counter("yurlib.scan.files", "outcome", outcome).increment(count);
    }

    private static String safeCode(String code) {
        return code == null || !code.matches("[A-Z0-9_]{1,80}") ? "UNCLASSIFIED" : code;
    }
}
