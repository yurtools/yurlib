package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yurlib.server.library.application.CandidateReconciliationResult;
import org.yurlib.server.library.application.CatalogLocationSnapshot;
import org.yurlib.server.library.application.CatalogReconciliation;
import org.yurlib.server.library.application.CatalogStore;
import org.yurlib.server.library.application.DefaultCatalogCandidateReconciler;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;
import org.yurlib.server.library.application.MetadataExtractor;
import org.yurlib.server.library.application.ScanDiscovery;

class DefaultCatalogCandidateReconcilerTest {

    private static final Instant MODIFIED_AT = Instant.parse("2026-09-29T12:00:00Z");
    private static final Instant OBSERVED_AT = MODIFIED_AT.plusSeconds(10);
    private static final Clock CLOCK = Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
    private static final UUID ROOT_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final String EXTRACTION_VERSION = "bounded-metadata-v1";

    private final CatalogStore catalog = mock(CatalogStore.class);
    private final MetadataExtractor extractor = mock(MetadataExtractor.class);
    private final DefaultCatalogCandidateReconciler reconciler =
            new DefaultCatalogCandidateReconciler(catalog, extractor, CLOCK);

    @Test
    void skipsAnUnchangedCandidateWithoutInvokingTheParser() {
        var candidate = candidate();
        when(catalog.findLocation(ROOT_ID, candidate.normalizedRelativePath()))
                .thenReturn(
                        Optional.of(new CatalogLocationSnapshot(123, MODIFIED_AT.plusNanos(999), EXTRACTION_VERSION)));

        var result = reconciler.reconcile(ROOT_ID, JOB_ID, EXTRACTION_VERSION, candidate);

        assertThat(result.state()).isEqualTo(CandidateReconciliationResult.State.SKIPPED);
        verify(extractor, never()).extract(candidate.containedFile());
        verify(catalog).markSeen(ROOT_ID, JOB_ID, candidate.normalizedRelativePath());
    }

    @Test
    void reparsesUnchangedBinaryFactsWhenTheExtractionVersionChanges() {
        var candidate = candidate();
        var metadata = metadata();
        when(catalog.findLocation(ROOT_ID, candidate.normalizedRelativePath()))
                .thenReturn(Optional.of(new CatalogLocationSnapshot(123, MODIFIED_AT, "bounded-metadata-v0")));
        when(extractor.extract(candidate.containedFile())).thenReturn(MetadataExtractionResult.extracted(metadata));

        var result = reconciler.reconcile(ROOT_ID, JOB_ID, EXTRACTION_VERSION, candidate);

        assertThat(result.state()).isEqualTo(CandidateReconciliationResult.State.PROCESSED);
        var reconciliation = ArgumentCaptor.forClass(CatalogReconciliation.class);
        verify(catalog).reconcile(reconciliation.capture());
        assertThat(reconciliation.getValue().extractionVersion()).isEqualTo(EXTRACTION_VERSION);
        assertThat(reconciliation.getValue().metadata()).isEqualTo(metadata);
    }

    @Test
    void preservesAnExistingLocationWhenExtractionFails() {
        var candidate = candidate();
        when(catalog.findLocation(ROOT_ID, candidate.normalizedRelativePath()))
                .thenReturn(
                        Optional.of(new CatalogLocationSnapshot(100, MODIFIED_AT.minusSeconds(1), EXTRACTION_VERSION)));
        when(extractor.extract(candidate.containedFile()))
                .thenReturn(MetadataExtractionResult.failed(
                        MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, "The file is corrupt."));

        var result = reconciler.reconcile(ROOT_ID, JOB_ID, EXTRACTION_VERSION, candidate);

        assertThat(result.state()).isEqualTo(CandidateReconciliationResult.State.FAILED);
        verify(catalog).markSeen(ROOT_ID, JOB_ID, candidate.normalizedRelativePath());
        verify(catalog, never()).reconcile(org.mockito.ArgumentMatchers.any());
    }

    private static ScanDiscovery.Candidate candidate() {
        return new ScanDiscovery.Candidate("fiction/book.epub", Path.of("book.epub"), 123, MODIFIED_AT, "key");
    }

    private static ExtractedBookMetadata metadata() {
        return new ExtractedBookMetadata(
                ExtractedBookMetadata.Format.EPUB,
                "A Book",
                List.of("An Author"),
                "en",
                Map.of("isbn", "9780000000001"),
                123,
                MODIFIED_AT,
                "epub-jaxp",
                "1");
    }
}
