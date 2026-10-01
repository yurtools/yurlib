package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yurlib.server.library.application.IngestionTaskStore;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.MissingLocationReconciler;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.application.ScanJobTelemetry;
import org.yurlib.server.library.application.ScanJobWorker;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.domain.ScanJob;

class ScanJobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Duration LEASE_TIMEOUT = Duration.ofMinutes(1);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final LibraryRootStore roots = mock(LibraryRootStore.class);
    private final ScanJobStore jobs = mock(ScanJobStore.class);
    private final ScanDiscovery discovery = mock(ScanDiscovery.class);
    private final IngestionTaskStore tasks = mock(IngestionTaskStore.class);
    private final MissingLocationReconciler reconciler = mock(MissingLocationReconciler.class);
    private final ScanJobTelemetry telemetry = mock(ScanJobTelemetry.class);
    private final ScanJobWorker worker =
            new ScanJobWorker(roots, jobs, discovery, tasks, reconciler, CLOCK, LEASE_TIMEOUT, telemetry, 256);

    @Test
    void returnsWithoutWorkWhenNoJobCanBeClaimed() {
        when(jobs.claimNext(NOW, NOW.minus(LEASE_TIMEOUT))).thenReturn(Optional.empty());

        assertThat(worker.runNext()).isFalse();
        verify(discovery, never()).discover(any(), any());
    }

    @Test
    void persistsEachOutcomeAndReconcilesOnlyAfterCompleteCoverage() {
        var root = root();
        var job = runningJob(root.id());
        when(jobs.claimNext(NOW, NOW.minus(LEASE_TIMEOUT))).thenReturn(Optional.of(job));
        when(roots.findById(root.id())).thenReturn(Optional.of(root));
        var candidate = new ScanDiscovery.Candidate("good.epub", Path.of("good.epub"), 12, NOW, "key");
        when(tasks.enqueue(root.id(), job.id(), job.extractionVersion(), candidate, 256, NOW))
                .thenReturn(true);
        when(discovery.discover(any(), any())).thenAnswer(invocation -> {
            ScanDiscovery.Listener listener = invocation.getArgument(1);
            listener.heartbeat();
            listener.failed("broken.epub", "FILE_UNREADABLE", "Unreadable");
            listener.discovered(candidate);
            return new ScanDiscovery.DiscoveryResult(true);
        });

        assertThat(worker.runNext()).isTrue();

        var outcomes = ArgumentCaptor.forClass(FileOutcome.class);
        verify(jobs).recordOutcome(outcomes.capture());
        assertThat(outcomes.getAllValues())
                .extracting(FileOutcome::normalizedRelativePath)
                .containsExactly("broken.epub");
        verify(jobs).heartbeat(job.id(), NOW);
        verify(tasks).enqueue(root.id(), job.id(), job.extractionVersion(), candidate, 256, NOW);
        verify(reconciler, never()).reconcileAfterCompleteScan(any(), any());
        verify(jobs).finishDiscovery(job.id(), true, NOW);
        verify(jobs).completeIfReady(job.id(), NOW);
        verify(telemetry).started(job);
        verify(telemetry).fileFailed("FILE_UNREADABLE");
    }

    @Test
    void doesNotReconcileWhenCoverageIsIncomplete() {
        var root = root();
        var job = runningJob(root.id());
        when(jobs.claimNext(NOW, NOW.minus(LEASE_TIMEOUT))).thenReturn(Optional.of(job));
        when(roots.findById(root.id())).thenReturn(Optional.of(root));
        when(discovery.discover(eq(root), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ScanDiscovery.DiscoveryResult(false));
        assertThat(worker.runNext()).isTrue();

        verify(reconciler, never()).reconcileAfterCompleteScan(any(), any());
        verify(jobs).finishDiscovery(job.id(), false, NOW);
        verify(jobs).completeIfReady(job.id(), NOW);
    }

    @Test
    void recordsASafeFailureWhenExecutionCannotContinue() {
        var root = root();
        var job = runningJob(root.id());
        when(jobs.claimNext(NOW, NOW.minus(LEASE_TIMEOUT))).thenReturn(Optional.of(job));
        when(roots.findById(root.id())).thenReturn(Optional.of(root));
        when(discovery.discover(eq(root), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new LibraryRootFailure(
                        LibraryRootFailure.Code.ROOT_IDENTITY_MISMATCH, "Sensitive detail must not be persisted."));
        when(jobs.fail(job.id(), "ROOT_IDENTITY_MISMATCH", NOW)).thenReturn(completedJob(job, ScanJob.State.FAILED));

        assertThat(worker.runNext()).isTrue();

        verify(jobs).fail(job.id(), "ROOT_IDENTITY_MISMATCH", NOW);
        verify(jobs, never()).complete(any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(telemetry).failed(any(), eq("ROOT_IDENTITY_MISMATCH"), eq(Duration.ZERO));
    }

    private static LibraryRoot root() {
        return new LibraryRoot(
                UUID.randomUUID(),
                "Main library",
                "main",
                "books",
                "a".repeat(64),
                LibraryRoot.Mode.READ_ONLY_SOURCE,
                LibraryRoot.Availability.AVAILABLE,
                null);
    }

    private static ScanJob runningJob(UUID rootId) {
        return new ScanJob(
                UUID.randomUUID(),
                rootId,
                ScanJob.State.RUNNING,
                "correlation-id",
                "discovery-v1",
                NOW,
                NOW,
                NOW,
                null,
                0,
                0,
                0,
                0,
                false,
                null);
    }

    private static ScanJob completedJob(ScanJob job, ScanJob.State state) {
        return new ScanJob(
                job.id(),
                job.libraryRootId(),
                state,
                job.correlationId(),
                job.extractionVersion(),
                job.createdAt(),
                job.startedAt(),
                NOW,
                NOW,
                2,
                1,
                0,
                1,
                true,
                state == ScanJob.State.FAILED ? "ROOT_IDENTITY_MISMATCH" : null);
    }
}
