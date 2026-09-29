package org.yurlib.server.library.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.domain.ScanJob;

class MicrometerScanJobTelemetryTest {

    @Test
    void recordsBoundedScanMetricsAndQueueDepth() {
        var registry = new SimpleMeterRegistry();
        var jobs = mock(ScanJobStore.class);
        when(jobs.queuedCount()).thenReturn(3L);
        var telemetry = new MicrometerScanJobTelemetry(registry, jobs);
        var completed = completedJob();

        telemetry.fileFailed("CORRUPT_ASSET");
        telemetry.fileFailed("unsafe-value/path");
        telemetry.completed(completed, Duration.ofMillis(125));

        assertThat(registry.get("yurlib.scan.queue.depth").gauge().value()).isEqualTo(3);
        assertThat(registry.get("yurlib.scan.jobs")
                        .tag("outcome", "COMPLETED_WITH_FAILURES")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("yurlib.scan.duration").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(125);
        assertThat(registry.get("yurlib.scan.files")
                        .tag("outcome", "processed")
                        .counter()
                        .count())
                .isEqualTo(7);
        assertThat(registry.get("yurlib.scan.parser.failures")
                        .tag("code", "CORRUPT_ASSET")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("yurlib.scan.parser.failures")
                        .tag("code", "UNCLASSIFIED")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    private static ScanJob completedJob() {
        var now = Instant.parse("2026-09-29T12:00:00Z");
        return new ScanJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ScanJob.State.COMPLETED_WITH_FAILURES,
                UUID.randomUUID().toString(),
                "discovery-v1",
                now.minusSeconds(1),
                now.minusMillis(125),
                now,
                now,
                10,
                7,
                2,
                1,
                true,
                null);
    }
}
