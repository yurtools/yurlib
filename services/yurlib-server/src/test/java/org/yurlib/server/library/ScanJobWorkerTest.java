package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.MissingLocationReconciler;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.application.ScanJobStore;
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
    private final MissingLocationReconciler reconciler = mock(MissingLocationReconciler.class);
    private final ScanJobWorker worker = new ScanJobWorker(roots, jobs, discovery, reconciler, CLOCK, LEASE_TIMEOUT);

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
        when(discovery.discover(any(), any())).thenAnswer(invocation -> {
            ScanDiscovery.Listener listener = invocation.getArgument(1);
            listener.heartbeat();
            listener.failed("broken.epub", "FILE_UNREADABLE", "Unreadable");
            listener.discovered("good.epub");
            return new ScanDiscovery.DiscoveryResult(true);
        });

        assertThat(worker.runNext()).isTrue();

        var outcomes = ArgumentCaptor.forClass(FileOutcome.class);
        verify(jobs, org.mockito.Mockito.times(2)).recordOutcome(outcomes.capture());
        assertThat(outcomes.getAllValues())
                .extracting(FileOutcome::normalizedRelativePath)
                .containsExactly("broken.epub", "good.epub");
        verify(jobs).heartbeat(job.id(), NOW);
        verify(reconciler).reconcileAfterCompleteScan(root.id(), job.id());
        verify(jobs).complete(job.id(), true, NOW);
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
        verify(jobs).complete(job.id(), false, NOW);
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

        assertThat(worker.runNext()).isTrue();

        verify(jobs).fail(job.id(), "ROOT_IDENTITY_MISMATCH", NOW);
        verify(jobs, never()).complete(any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    private static LibraryRoot root() {
        return new LibraryRoot(
                UUID.randomUUID(),
                "Main library",
                "main",
                "books",
                "a".repeat(64),
                LibraryRoot.Mode.READ_ONLY,
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
}
