package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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
    }

    @Test
    void configuresScansRescansAndDownloadsOriginalBytesWithoutChangingSources() throws IOException {
        var root = roots.configure(
                new ConfigureLibraryRootCommand("Acceptance library", "acceptance", "valid", IDENTITY_TOKEN));

        var first = scans.queue(root.id(), "00000000-0000-0000-0000-000000000022");
        assertThat(worker.runNext()).isTrue();
        var completed = scans.get(first.id()).job();
        assertThat(completed.state()).isEqualTo(ScanJob.State.SUCCEEDED);
        assertThat(completed.processedCount()).isEqualTo(4);

        var firstCatalog = catalog.search(null, 0, 25);
        assertThat(firstCatalog.totalElements()).isEqualTo(4);
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
        assertThat(worker.runNext()).isTrue();
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
        assertThat(rescanned.skippedCount()).isEqualTo(4);
        assertThat(catalog.search(null, 0, 25).items().stream()
                        .flatMap(work -> work.assets().stream())
                        .map(CatalogQuery.AssetSummary::id)
                        .collect(Collectors.toSet()))
                .isEqualTo(assetIds);
        FixtureCorpus.assertSourcesUnchanged(SOURCE_HASHES);
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
