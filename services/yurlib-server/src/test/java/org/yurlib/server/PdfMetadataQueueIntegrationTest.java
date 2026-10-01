package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.CandidateReconciliationResult;
import org.yurlib.server.library.application.CatalogQuery;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.PdfMetadataQueue;
import org.yurlib.server.library.application.PdfWorkerJobService;
import org.yurlib.server.library.application.PdfWorkerResult;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.domain.Asset;
import org.yurlib.server.library.domain.LibraryRoot;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class PdfMetadataQueueIntegrationTest {

    private static final Path STAGING = createTemporaryDirectory("yurlib-pdf-staging-");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("yurlib.pdf-worker.staging-directory", STAGING::toString);
    }

    @Autowired
    private LibraryRootStore roots;

    @Autowired
    private ScanJobStore scans;

    @Autowired
    private PdfMetadataQueue queue;

    @Autowired
    private PdfWorkerJobService workerJobs;

    @Autowired
    private CatalogQuery catalog;

    @Test
    void stagesClaimsAndCompletesPdfMetadataWithoutChangingTheSource() throws Exception {
        var sourceDirectory = Files.createTempDirectory("yurlib-pdf-source-");
        var source = sourceDirectory.resolve("document.pdf");
        Files.writeString(source, "%PDF-1.7\n% generated queue fixture\n", StandardCharsets.US_ASCII);
        var sourceHash = sha256(source);
        var root = roots.save(new LibraryRoot(
                UUID.randomUUID(),
                "PDF library",
                "main",
                "pdf",
                "a".repeat(64),
                LibraryRoot.Mode.READ_ONLY_SOURCE,
                LibraryRoot.Availability.AVAILABLE,
                null));
        var scan = scans.queue(root.id(), "pdf-queue-test", "bounded-metadata-v4", java.time.Instant.now());
        var candidate = new ScanDiscovery.Candidate(
                "documents/document.pdf",
                source,
                Files.size(source),
                Files.getLastModifiedTime(source).toInstant(),
                "generated-file-key");

        assertThat(queue.stageAndQueue(root.id(), scan.id(), "bounded-metadata-v4", candidate)
                        .state())
                .isEqualTo(CandidateReconciliationResult.State.PROCESSED);
        assertThat(catalog.search(null, Set.of(Asset.Format.PDF), 0, 25).items())
                .singleElement()
                .satisfies(work -> assertThat(work.assets().getFirst().metadataState())
                        .isEqualTo(org.yurlib.server.library.application.CatalogReconciliation.MetadataState.PENDING));

        var claim = workerJobs.claim().orElseThrow();
        assertThat(Files.readAllBytes(workerJobs.leasedInput(claim.id(), claim.leaseToken())))
                .isEqualTo(Files.readAllBytes(source));
        workerJobs.complete(
                claim.id(),
                claim.leaseToken(),
                new PdfWorkerResult(
                        PdfWorkerResult.State.EXTRACTED,
                        "Изолированный PDF",
                        List.of("Анна Тестова"),
                        "ru",
                        Map.of("xmp", "urn:yurlib:pdf-test"),
                        Map.of(
                                "pdf:info:title", List.of("Information title"),
                                "pdf:xmp:title", List.of("Изолированный PDF")),
                        1,
                        "apache-pdfbox",
                        "3.0.8-1",
                        null,
                        null));

        assertThat(catalog.search("изолированный", Set.of(Asset.Format.PDF), 0, 25)
                        .items())
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.contributors()).containsExactly("Анна Тестова");
                    assertThat(work.assets().getFirst().metadataState())
                            .isEqualTo(org.yurlib.server.library.application.CatalogReconciliation.MetadataState.READY);
                });
        assertThat(sha256(source)).isEqualTo(sourceHash);
        assertThat(Files.exists(source)).isTrue();
        try (var staged = Files.list(STAGING)) {
            assertThat(staged).isEmpty();
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Path createTemporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
