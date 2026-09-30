package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.AssetContentStore;
import org.yurlib.server.library.application.CatalogQuery;
import org.yurlib.server.library.application.CatalogReconciliation;
import org.yurlib.server.library.application.CatalogStore;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.ScanJobFailure;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.domain.ScanJob;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class YurlibServerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LibraryRootStore libraryRootStore;

    @Autowired
    private ScanJobStore scanJobStore;

    @Autowired
    private CatalogStore catalogStore;

    @Autowired
    private CatalogQuery catalogQuery;

    @Autowired
    private AssetContentStore assetContentStore;

    @Test
    void appliesFlywayMigrations() {
        var value = JdbcClient.create(dataSource).sql("""
                SELECT metadata_value
                FROM yurlib_metadata
                WHERE metadata_key = 'schema_version'
                """).query(String.class).single();

        assertThat(value).isEqualTo("3");
    }

    @Test
    void createsTheLocalLibraryTablesFromAnEmptyDatabase() {
        var tables = JdbcClient.create(dataSource).sql("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """).query(String.class).list();

        assertThat(tables)
                .containsAll(Set.of(
                        "library_root",
                        "scan_job",
                        "file_outcome",
                        "work",
                        "edition",
                        "asset",
                        "asset_location",
                        "metadata_observation"));
    }

    @Test
    void enforcesOneActiveScanPerLibraryRoot() {
        var client = JdbcClient.create(dataSource);
        var rootId = UUID.randomUUID();
        client.sql("""
                INSERT INTO library_root (
                    id, name, mount_alias, relative_base_path, expected_identity_digest
                ) VALUES (
                    :id, 'Main library', 'library-main', '', :digest
                )
                """).param("id", rootId).param("digest", "0".repeat(64)).update();
        insertQueuedScan(client, rootId, UUID.randomUUID());

        assertThatThrownBy(() -> insertQueuedScan(client, rootId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsClaimsOutcomesCountersAndCompletion() {
        var root = saveRoot();
        var createdAt = java.time.Instant.parse("2026-09-29T10:00:00Z");
        var claimedAt = createdAt.plusSeconds(10);
        var queued = scanJobStore.queue(root.id(), "correlation-id", "discovery-v1", createdAt);

        var claimed =
                scanJobStore.claimNext(claimedAt, claimedAt.minusSeconds(60)).orElseThrow();
        scanJobStore.recordOutcome(new FileOutcome(
                claimed.id(), "good.epub", FileOutcome.State.DISCOVERED, null, null, 1, claimedAt.plusSeconds(1)));
        scanJobStore.recordOutcome(new FileOutcome(
                claimed.id(),
                "broken.epub",
                FileOutcome.State.FAILED,
                "FILE_UNREADABLE",
                "A library entry could not be inspected.",
                1,
                claimedAt.plusSeconds(2)));
        var completed = scanJobStore.complete(claimed.id(), true, claimedAt.plusSeconds(3));

        assertThat(queued.state()).isEqualTo(ScanJob.State.QUEUED);
        assertThat(claimed.state()).isEqualTo(ScanJob.State.RUNNING);
        assertThat(completed.state()).isEqualTo(ScanJob.State.COMPLETED_WITH_FAILURES);
        assertThat(completed.discoveredCount()).isEqualTo(2);
        assertThat(completed.failedCount()).isEqualTo(1);
        assertThat(completed.completionCoverage()).isTrue();
        assertThat(scanJobStore.findFailures(claimed.id(), 100))
                .singleElement()
                .extracting(FileOutcome::normalizedRelativePath)
                .isEqualTo("broken.epub");
    }

    @Test
    void reclaimsOnlyAnExpiredRunningJob() {
        var root = saveRoot();
        var createdAt = java.time.Instant.parse("2026-09-29T10:00:00Z");
        var queued = scanJobStore.queue(root.id(), "correlation-id", "discovery-v1", createdAt);
        var firstClaim =
                scanJobStore.claimNext(createdAt.plusSeconds(10), createdAt).orElseThrow();

        assertThat(scanJobStore.claimNext(createdAt.plusSeconds(30), createdAt.minusSeconds(40)))
                .isEmpty();

        var reclaimed = scanJobStore
                .claimNext(createdAt.plusSeconds(80), createdAt.plusSeconds(20))
                .orElseThrow();
        assertThat(reclaimed.id()).isEqualTo(queued.id());
        assertThat(reclaimed.startedAt()).isEqualTo(firstClaim.startedAt());
        assertThat(reclaimed.heartbeatAt()).isEqualTo(createdAt.plusSeconds(80));
    }

    @Test
    void translatesTheActiveScanConstraintToADomainFailure() {
        var root = saveRoot();
        scanJobStore.queue(root.id(), "first-correlation", "discovery-v1", java.time.Instant.now());

        assertThatThrownBy(() ->
                        scanJobStore.queue(root.id(), "second-correlation", "discovery-v1", java.time.Instant.now()))
                .isInstanceOfSatisfying(
                        ScanJobFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(ScanJobFailure.Code.SCAN_ALREADY_ACTIVE));
    }

    @Test
    void rejectsNonNormalizedStoredPaths() {
        var client = JdbcClient.create(dataSource);

        assertThatThrownBy(() -> client.sql("""
                INSERT INTO library_root (
                    id, name, mount_alias, relative_base_path, expected_identity_digest
                ) VALUES (
                    :id, 'Escaping library', 'library-escape', :path, :digest
                )
                """)
                        .param("id", UUID.randomUUID())
                        .param("path", "books\\..\\outside")
                        .param("digest", "0".repeat(64))
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsOnlyTheIdentityDigestForAConfiguredRoot() {
        var identityDigest = "a".repeat(64);
        var saved = libraryRootStore.save(new LibraryRoot(
                UUID.randomUUID(),
                "Main library",
                "main",
                "books",
                identityDigest,
                LibraryRoot.Mode.READ_ONLY,
                LibraryRoot.Availability.AVAILABLE,
                null));

        assertThat(libraryRootStore.findAll()).containsExactly(saved);
        var storedDigest = JdbcClient.create(dataSource)
                .sql("""
                SELECT expected_identity_digest
                FROM library_root
                WHERE id = :id
                """)
                .param("id", saved.id())
                .query(String.class)
                .single();
        assertThat(storedDigest).isEqualTo(identityDigest);
    }

    @Test
    void reconcilesOneCandidateTransactionallyAndKeepsParserReprocessingIdempotent() {
        var root = saveRoot();
        var firstJob = scanJobStore.queue(root.id(), "first", "bounded-metadata-v1", java.time.Instant.now());
        var first = reconciliation(root.id(), firstJob.id(), "bounded-metadata-v1", metadata("A Book", 123));

        catalogStore.reconcile(first);
        catalogStore.reconcile(
                reconciliation(root.id(), firstJob.id(), "bounded-metadata-v2", metadata("A Better Book", 123)));

        var client = JdbcClient.create(dataSource);
        assertThat(tableCount(client, "work")).isEqualTo(1);
        assertThat(tableCount(client, "edition")).isEqualTo(1);
        assertThat(tableCount(client, "asset")).isEqualTo(1);
        assertThat(tableCount(client, "asset_location")).isEqualTo(1);
        assertThat(client.sql("SELECT extraction_version FROM asset")
                        .query(String.class)
                        .single())
                .isEqualTo("bounded-metadata-v2");
        assertThat(client.sql("SELECT provisional_title FROM work")
                        .query(String.class)
                        .single())
                .isEqualTo("A Better Book");
    }

    @Test
    void searchesCatalogByTitleContributorFilenameAndIdentifierWithBoundedPagination() {
        var root = saveRoot();
        var job = scanJobStore.queue(root.id(), "search", "bounded-metadata-v1", java.time.Instant.now());
        catalogStore.reconcile(
                reconciliation(root.id(), job.id(), "bounded-metadata-v1", metadata("The Left Hand of Darkness", 123)));
        catalogStore.reconcile(reconciliation(
                root.id(),
                job.id(),
                "poetry/always-coming-home.epub",
                "bounded-metadata-v1",
                metadata("Always Coming Home", 456)));

        for (var query : List.of("left hand", "ursula", "fiction/book.epub", "9780000000001")) {
            var page = catalogQuery.search(query, 0, 25);
            var matchesBothBooks = query.equals("ursula") || query.equals("9780000000001");
            assertThat(page.totalElements()).isEqualTo(matchesBothBooks ? 2 : 1);
            if (!matchesBothBooks) {
                assertThat(page.items()).singleElement().satisfies(item -> {
                    assertThat(item.title()).isEqualTo("The Left Hand of Darkness");
                    assertThat(item.contributors()).containsExactly("Ursula Le Guin");
                    assertThat(item.assets()).singleElement().satisfies(asset -> {
                        assertThat(asset.format()).isEqualTo(org.yurlib.server.library.domain.Asset.Format.EPUB);
                        assertThat(asset.availability()).isEqualTo(CatalogQuery.Availability.AVAILABLE);
                    });
                });
            }
        }

        assertThat(catalogQuery.search(null, 0, 1).items())
                .extracting(CatalogQuery.WorkSummary::title)
                .containsExactly("Always Coming Home");
        assertThat(catalogQuery.search(null, 1, 1).items())
                .extracting(CatalogQuery.WorkSummary::title)
                .containsExactly("The Left Hand of Darkness");

        assertThatThrownBy(() -> catalogQuery.search("query", 0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalogQuery.search("x".repeat(201), 0, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistsReturnsAndSearchesCyrillicCatalogMetadata() {
        var root = saveRoot();
        var job = scanJobStore.queue(root.id(), "cyrillic-search", "bounded-metadata-v1", java.time.Instant.now());
        var metadata = new ExtractedBookMetadata(
                ExtractedBookMetadata.Format.FB2,
                "Кириллическая книга",
                List.of("Анна Тестова"),
                "ru",
                Map.of(),
                512,
                java.time.Instant.parse("2026-09-29T12:00:00Z"),
                "jdk-fb2",
                "1");
        catalogStore.reconcile(
                reconciliation(root.id(), job.id(), "русские/кириллица.fb2", "bounded-metadata-v1", metadata));

        for (var query : List.of("кириллическая", "ТЕСТОВА", "кириллица.fb2")) {
            assertThat(catalogQuery.search(query, 0, 25).items())
                    .singleElement()
                    .satisfies(work -> {
                        assertThat(work.title()).isEqualTo("Кириллическая книга");
                        assertThat(work.contributors()).containsExactly("Анна Тестова");
                    });
        }
        assertThat(JdbcClient.create(dataSource)
                        .sql("SELECT observed_language FROM edition")
                        .query(String.class)
                        .single())
                .isEqualTo("ru");
    }

    @Test
    void loadsAStoredDownloadLocationByOpaqueAssetIdentifier() {
        var root = saveRoot();
        var job = scanJobStore.queue(root.id(), "download", "bounded-metadata-v1", java.time.Instant.now());
        catalogStore.reconcile(reconciliation(root.id(), job.id(), "bounded-metadata-v1", metadata("A Book", 123)));
        var assetId = JdbcClient.create(dataSource)
                .sql("SELECT id FROM asset")
                .query(UUID.class)
                .single();

        assertThat(assetContentStore.findByAssetId(assetId)).hasValueSatisfying(location -> {
            assertThat(location.assetId()).isEqualTo(assetId);
            assertThat(location.rootId()).isEqualTo(root.id());
            assertThat(location.normalizedRelativePath()).isEqualTo("fiction/book.epub");
            assertThat(location.byteSize()).isEqualTo(123);
            assertThat(location.fileKey()).isEqualTo("file-key");
            assertThat(location.availability())
                    .isEqualTo(org.yurlib.server.library.domain.AssetLocation.Availability.AVAILABLE);
        });
    }

    @Test
    void marksOnlyLocationsUnseenByACompleteScanAsMissing() {
        var root = saveRoot();
        var firstJob = scanJobStore.queue(root.id(), "first", "bounded-metadata-v1", java.time.Instant.now());
        catalogStore.reconcile(
                reconciliation(root.id(), firstJob.id(), "bounded-metadata-v1", metadata("A Book", 123)));
        var client = JdbcClient.create(dataSource);
        client.sql("UPDATE scan_job SET state = 'SUCCEEDED' WHERE id = :id")
                .param("id", firstJob.id())
                .update();
        var secondJob = scanJobStore.queue(root.id(), "second", "bounded-metadata-v1", java.time.Instant.now());

        catalogStore.markUnseenMissing(root.id(), secondJob.id());

        assertThat(client.sql("SELECT availability FROM asset_location")
                        .query(String.class)
                        .single())
                .isEqualTo("MISSING");
        assertThat(tableCount(client, "work")).isEqualTo(1);
        assertThat(tableCount(client, "asset")).isEqualTo(1);
    }

    private LibraryRoot saveRoot() {
        return libraryRootStore.save(new LibraryRoot(
                UUID.randomUUID(),
                "Main library",
                "main",
                "books",
                "a".repeat(64),
                LibraryRoot.Mode.READ_ONLY,
                LibraryRoot.Availability.AVAILABLE,
                null));
    }

    private static void insertQueuedScan(JdbcClient client, UUID rootId, UUID jobId) {
        client.sql("""
                INSERT INTO scan_job (
                    id, library_root_id, state, correlation_id, extraction_version
                ) VALUES (
                    :id, :rootId, 'QUEUED', :correlationId, 'extractor-v1'
                )
                """)
                .param("id", jobId)
                .param("rootId", rootId)
                .param("correlationId", jobId.toString())
                .update();
    }

    private static CatalogReconciliation reconciliation(
            UUID rootId, UUID jobId, String extractionVersion, ExtractedBookMetadata metadata) {
        return reconciliation(rootId, jobId, "fiction/book.epub", extractionVersion, metadata);
    }

    private static CatalogReconciliation reconciliation(
            UUID rootId,
            UUID jobId,
            String normalizedRelativePath,
            String extractionVersion,
            ExtractedBookMetadata metadata) {
        return new CatalogReconciliation(
                rootId,
                jobId,
                normalizedRelativePath,
                "file-key",
                extractionVersion,
                metadata,
                java.time.Instant.parse("2026-09-29T12:00:10Z"));
    }

    private static ExtractedBookMetadata metadata(String title, long byteSize) {
        return new ExtractedBookMetadata(
                ExtractedBookMetadata.Format.EPUB,
                title,
                List.of("Ursula Le Guin"),
                "en",
                Map.of("isbn", "9780000000001"),
                byteSize,
                java.time.Instant.parse("2026-09-29T12:00:00Z"),
                "epub-jaxp",
                "1");
    }

    private static long tableCount(JdbcClient client, String table) {
        return client.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
