package org.yurlib.server.library.application;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

public final class DefaultCatalogCandidateReconciler implements CatalogCandidateReconciler {

    private final CatalogStore catalog;
    private final MetadataExtractor extractor;
    private final PdfMetadataQueue pdfQueue;
    private final Clock clock;

    public DefaultCatalogCandidateReconciler(CatalogStore catalog, MetadataExtractor extractor, Clock clock) {
        this(
                catalog,
                extractor,
                (rootId, scanJobId, extractionVersion, candidate) -> CandidateReconciliationResult.deferred(
                        MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT.name(),
                        "The isolated PDF metadata worker is not configured."),
                clock);
    }

    public DefaultCatalogCandidateReconciler(
            CatalogStore catalog, MetadataExtractor extractor, PdfMetadataQueue pdfQueue, Clock clock) {
        this.catalog = catalog;
        this.extractor = extractor;
        this.pdfQueue = pdfQueue;
        this.clock = clock;
    }

    @Override
    public CandidateReconciliationResult reconcile(
            UUID rootId, UUID scanJobId, String extractionVersion, ScanDiscovery.Candidate candidate) {
        var current = catalog.findLocation(rootId, candidate.normalizedRelativePath());
        if (current.filter(location -> unchanged(location, candidate, extractionVersion))
                .isPresent()) {
            catalog.markSeen(rootId, scanJobId, candidate.normalizedRelativePath());
            return CandidateReconciliationResult.skipped();
        }

        if (candidate
                .normalizedRelativePath()
                .toLowerCase(java.util.Locale.ROOT)
                .endsWith(".pdf")) {
            return pdfQueue.stageAndQueue(rootId, scanJobId, extractionVersion, candidate);
        }

        var extraction = extractor.extract(candidate.containedFile());
        if (extraction.state() != MetadataExtractionResult.State.EXTRACTED) {
            current.ifPresent(ignored -> catalog.markSeen(rootId, scanJobId, candidate.normalizedRelativePath()));
            return failedExtraction(extraction);
        }

        catalog.reconcile(new CatalogReconciliation(
                rootId,
                scanJobId,
                candidate.normalizedRelativePath(),
                candidate.fileKey(),
                extractionVersion,
                extraction.metadata(),
                clock.instant()));
        return CandidateReconciliationResult.processed();
    }

    private static boolean unchanged(
            CatalogLocationSnapshot location, ScanDiscovery.Candidate candidate, String extractionVersion) {
        return location.byteSize() == candidate.byteSize()
                && timestampMatches(location.modifiedAt(), candidate.modifiedAt())
                && location.extractionVersion().equals(extractionVersion)
                && location.metadataState() != CatalogReconciliation.MetadataState.FAILED_SAFE;
    }

    private static boolean timestampMatches(java.time.Instant first, java.time.Instant second) {
        return Duration.between(first, second).abs().compareTo(Duration.ofNanos(1_000)) <= 0;
    }

    private static CandidateReconciliationResult failedExtraction(MetadataExtractionResult extraction) {
        var code = extraction.errorCode().name();
        if (extraction.state() == MetadataExtractionResult.State.DEFERRED) {
            return CandidateReconciliationResult.deferred(code, extraction.safeDiagnostic());
        }
        return CandidateReconciliationResult.failed(code, extraction.safeDiagnostic());
    }
}
