package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.AssetContentStore;
import org.yurlib.server.library.application.CatalogQuery;
import org.yurlib.server.library.application.ConfigureLibraryRootCommand;
import org.yurlib.server.library.application.IngestionTaskWorker;
import org.yurlib.server.library.application.LibraryRootUseCases;
import org.yurlib.server.library.application.OriginalAssetContentUseCases;
import org.yurlib.server.library.application.ScanJobUseCases;
import org.yurlib.server.library.application.ScanJobWorker;
import org.yurlib.server.library.domain.ScanJob;
import org.yurlib.server.testing.fixtures.FixtureCorpus;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class M1WalkingSkeletonAcceptanceTest {

    private static final String IDENTITY_TOKEN = "m1-acceptance-private-token";
    private static final Path MOUNT = materializedFixtureMount();
    private static final Path PARALLEL_MOUNT = materializedFixtureMount();
    private static final Map<String, String> SOURCE_HASHES = sourceHashes();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private LibraryRootUseCases roots;

    @Autowired
    private ScanJobUseCases scans;

    @Autowired
    private ScanJobWorker worker;

    @Autowired
    private IngestionTaskWorker taskWorker;

    @Autowired
    private CatalogQuery catalog;

    @Autowired
    private OriginalAssetContentUseCases content;

    @Autowired
    private AssetContentStore contentStore;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void libraryMount(DynamicPropertyRegistry properties) {
        properties.add("yurlib.library.mounts[0].alias", () -> "acceptance");
        properties.add("yurlib.library.mounts[0].path", MOUNT::toString);
        properties.add("yurlib.library.mounts[1].alias", () -> "acceptance-parallel");
        properties.add("yurlib.library.mounts[1].path", PARALLEL_MOUNT::toString);
    }

    @Test
    void configuresScansRescansAndDownloadsOriginalBytesWithoutChangingSources() throws IOException {
        var root = roots.configure(
                new ConfigureLibraryRootCommand("Acceptance library", "acceptance", "valid", IDENTITY_TOKEN));

        var first = scans.queue(root.id(), "00000000-0000-0000-0000-000000000022");
        runScan();
        var completed = scans.get(first.id()).job();
        assertThat(completed.state()).isEqualTo(ScanJob.State.SUCCEEDED);
        assertThat(completed.processedCount()).isEqualTo(6);

        var firstCatalog = catalog.search(null, 0, 25);
        assertThat(firstCatalog.totalElements()).isEqualTo(6);
        assertThat(firstCatalog.items())
                .extracting(CatalogQuery.WorkSummary::title)
                .contains("Кириллическая MOBI книга", "Updated title");
        assertThat(catalog.search("тестова", 0, 25).items()).anySatisfy(work -> {
            assertThat(work.title()).isEqualTo("Кириллическая книга");
            assertThat(work.contributors()).containsExactly("Анна Тестова");
        });
        var asset = firstCatalog.items().stream()
                .filter(work -> "Minimal EPUB Fixture".equals(work.title()))
                .flatMap(work -> work.assets().stream())
                .findFirst()
                .orElseThrow();
        var location = contentStore.findByAssetId(asset.id()).orElseThrow();
        var source = MOUNT.resolve("valid/minimal.epub");
        var attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(location.byteSize()).isEqualTo(attributes.size());
        assertThat(Duration.between(
                                location.modifiedAt(),
                                attributes.lastModifiedTime().toInstant())
                        .abs())
                .isLessThanOrEqualTo(Duration.ofNanos(1_000));
        assertThat(location.fileKey()).isEqualTo(attributes.fileKey().toString());
        try (var opened = content.open(asset.id()).content()) {
            assertThat(opened.readAllBytes()).isEqualTo(Files.readAllBytes(source));
        }

        Set<?> assetIds = firstCatalog.items().stream()
                .flatMap(work -> work.assets().stream())
                .map(CatalogQuery.AssetSummary::id)
                .collect(Collectors.toSet());
        var second = scans.queue(root.id(), "00000000-0000-0000-0000-000000000023");
        runScan();
        var rescanned = scans.get(second.id()).job();
        var outcomes = JdbcClient.create(dataSource)
                .sql("SELECT normalized_relative_path, state FROM file_outcome WHERE scan_job_id = :jobId ORDER BY 1")
                .param("jobId", second.id())
                .query()
                .listOfRows();
        assertThat(rescanned.state()).isEqualTo(ScanJob.State.SUCCEEDED);
        assertThat(rescanned.processedCount())
                .as("second scan outcomes: %s", outcomes)
                .isZero();
        assertThat(rescanned.skippedCount()).isEqualTo(6);
        assertThat(catalog.search(null, 0, 25).items().stream()
                        .flatMap(work -> work.assets().stream())
                        .map(CatalogQuery.AssetSummary::id)
                        .collect(Collectors.toSet()))
                .isEqualTo(assetIds);

        var parallelRoot = roots.configure(new ConfigureLibraryRootCommand(
                "Parallel acceptance library", "acceptance-parallel", "valid", IDENTITY_TOKEN));
        var parallel = scans.queue(parallelRoot.id(), "00000000-0000-0000-0000-000000000024");
        assertThat(worker.runNext()).isTrue();
        runTasksInParallel();
        assertThat(scans.get(parallel.id()).job().state()).isEqualTo(ScanJob.State.SUCCEEDED);
        assertThat(observations(parallelRoot.id())).isEqualTo(observations(root.id()));
        FixtureCorpus.assertSourcesUnchanged(SOURCE_HASHES);
    }

    private void runScan() {
        assertThat(worker.runNext()).isTrue();
        while (taskWorker.runNext()) {
            // Drain the durable task queue deterministically for this acceptance test.
        }
    }

    private void runTasksInParallel() {
        try (var executor = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory())) {
            var results = executor.invokeAll(List.of(this::drainTasks, this::drainTasks));
            for (var result : results) {
                result.get();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Parallel ingestion was interrupted.", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Parallel ingestion failed.", failure.getCause());
        }
    }

    private Boolean drainTasks() {
        while (taskWorker.runNext()) {
            // Drain until no durable task can be claimed by this worker.
        }
        return true;
    }

    private List<Map<String, Object>> observations(java.util.UUID rootId) {
        return JdbcClient.create(dataSource)
                .sql("""
                        SELECT field_name, COALESCE(observed_value, '<absent>') AS observed_value,
                               value_state, value_ordinal, parser_name, parser_version
                        FROM metadata_observation
                        WHERE source_root_id = :rootId
                        ORDER BY field_name, observed_value, value_state, value_ordinal,
                                 parser_name, parser_version
                        """)
                .param("rootId", rootId)
                .query()
                .listOfRows();
    }

    private static Path materializedFixtureMount() {
        try {
            var mount = Files.createTempDirectory("yurlib-m1-acceptance-");
            FixtureCorpus.materialize(mount);
            Files.writeString(
                    mount.resolve("valid/.yurlib-root-id"),
                    IDENTITY_TOKEN + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            return mount;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Map<String, String> sourceHashes() {
        try {
            return FixtureCorpus.sourceHashes();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
