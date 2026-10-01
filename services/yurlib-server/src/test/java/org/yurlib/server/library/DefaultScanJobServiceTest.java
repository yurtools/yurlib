package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yurlib.server.library.application.DefaultScanJobService;
import org.yurlib.server.library.application.IngestionTaskStore;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.MetadataExtractor;
import org.yurlib.server.library.application.ScanJobFailure;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.domain.ScanJob;

class DefaultScanJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final LibraryRootStore roots = mock(LibraryRootStore.class);
    private final ScanJobStore jobs = mock(ScanJobStore.class);
    private final MetadataExtractor extractor = mock(MetadataExtractor.class);
    private final IngestionTaskStore tasks = mock(IngestionTaskStore.class);
    private final DefaultScanJobService service =
            new DefaultScanJobService(roots, jobs, ignored -> true, extractor, tasks, CLOCK);

    @Test
    void queuesAJobOnlyForAConfiguredRoot() {
        var root = root();
        var queued = job(root.id(), ScanJob.State.QUEUED);
        when(extractor.extractionVersion()).thenReturn("bounded-metadata-v1");
        when(roots.findById(root.id())).thenReturn(Optional.of(root));
        when(jobs.queue(root.id(), "correlation-id", "bounded-metadata-v1", NOW))
                .thenReturn(queued);

        assertThat(service.queue(root.id(), "correlation-id")).isEqualTo(queued);
    }

    @Test
    void rejectsAnUnknownRootBeforeCreatingAJob() {
        var rootId = UUID.randomUUID();
        when(roots.findById(rootId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.queue(rootId, "correlation-id"))
                .isInstanceOfSatisfying(
                        ScanJobFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(ScanJobFailure.Code.ROOT_NOT_FOUND));
    }

    @Test
    void returnsTheJobWithABoundedFailureList() {
        var scanJob = job(root().id(), ScanJob.State.COMPLETED_WITH_FAILURES);
        var failure = new FileOutcome(
                scanJob.id(), "broken.epub", FileOutcome.State.FAILED, "FILE_UNREADABLE", "Unreadable", 1, NOW);
        when(jobs.findById(scanJob.id())).thenReturn(Optional.of(scanJob));
        when(jobs.findFailures(scanJob.id(), 100)).thenReturn(List.of(failure));

        assertThat(service.get(scanJob.id()).failures()).containsExactly(failure);
        verify(jobs).findFailures(scanJob.id(), 100);
    }

    @Test
    void cancelsQueuedTasksAndReturnsTheDurableJobState() {
        var queued = job(root().id(), ScanJob.State.QUEUED);
        var cancelled = job(queued.libraryRootId(), ScanJob.State.CANCELLED);
        when(jobs.findById(queued.id())).thenReturn(Optional.of(queued));
        when(jobs.cancel(queued.id(), NOW)).thenReturn(cancelled);
        when(jobs.findById(cancelled.id())).thenReturn(Optional.of(cancelled));
        when(jobs.findFailures(cancelled.id(), 100)).thenReturn(List.of());

        assertThat(service.cancel(queued.id()).job().state()).isEqualTo(ScanJob.State.CANCELLED);
        verify(tasks).cancelQueued(queued.id(), NOW);
        verify(jobs).completeIfReady(queued.id(), NOW);
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

    private static ScanJob job(UUID rootId, ScanJob.State state) {
        return new ScanJob(
                UUID.randomUUID(),
                rootId,
                state,
                "correlation-id",
                "discovery-v1",
                NOW,
                null,
                null,
                state == ScanJob.State.QUEUED ? null : NOW,
                state == ScanJob.State.COMPLETED_WITH_FAILURES ? 1 : 0,
                0,
                0,
                state == ScanJob.State.COMPLETED_WITH_FAILURES ? 1 : 0,
                false,
                null);
    }
}
