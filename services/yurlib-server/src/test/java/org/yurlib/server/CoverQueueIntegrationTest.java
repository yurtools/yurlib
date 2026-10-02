package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.CatalogReconciliation;
import org.yurlib.server.library.application.CatalogStore;
import org.yurlib.server.library.application.CoverContentStore;
import org.yurlib.server.library.application.CoverQueue;
import org.yurlib.server.library.application.CoverWorkerJobService;
import org.yurlib.server.library.application.CoverWorkerResult;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.domain.Asset;
import org.yurlib.server.library.domain.LibraryRoot;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class CoverQueueIntegrationTest {

    private static final String MARKER = "cover-output-marker-token";
    private static final Path MOUNT = mountDirectory();
    private static final Path MANAGED = MOUNT.resolve("managed");
    private static final Path STAGING = temporaryDirectory("yurlib-cover-staging-");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("yurlib.cover-worker.staging-directory", STAGING::toString);
        registry.add("yurlib.library.mounts[0].alias", () -> "managed-test");
        registry.add("yurlib.library.mounts[0].path", MOUNT::toString);
    }

    @Autowired
    private LibraryRootStore roots;

    @Autowired
    private ScanJobStore scans;

    @Autowired
    private CatalogStore catalog;

    @Autowired
    private CoverQueue queue;

    @Autowired
    private CoverWorkerJobService jobs;

    @Autowired
    private CoverContentStore covers;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void publishesValidatedCoverAtomicallyAndExposesOnlyTheOpaquePath() throws Exception {
        var sourceRoot = roots.save(new LibraryRoot(
                UUID.randomUUID(),
                "Source",
                "managed-test",
                "source",
                sha256(MARKER.getBytes(StandardCharsets.UTF_8)),
                LibraryRoot.Mode.READ_ONLY_SOURCE,
                LibraryRoot.Availability.AVAILABLE,
                null));
        roots.save(new LibraryRoot(
                UUID.randomUUID(),
                "Managed covers",
                "managed-test",
                "managed",
                sha256(MARKER.getBytes(StandardCharsets.UTF_8)),
                LibraryRoot.Mode.MANAGED_OUTPUT,
                LibraryRoot.Availability.AVAILABLE,
                null,
                true));
        var scan = scans.queue(sourceRoot.id(), "cover-test", "cover-test", Instant.now());
        var source = Files.createTempFile("cover-source-", ".epub");
        Files.writeString(source, "cover queue fixture", StandardCharsets.UTF_8);
        var assetId = catalog.reconcile(new CatalogReconciliation(
                sourceRoot.id(),
                scan.id(),
                "fixture.epub",
                "fixture-key",
                "cover-test",
                new ExtractedBookMetadata(
                        ExtractedBookMetadata.Format.EPUB,
                        "Covered work",
                        List.of(),
                        "en",
                        Map.of(),
                        Map.of(),
                        Files.size(source),
                        Files.getLastModifiedTime(source).toInstant(),
                        "fixture",
                        "1"),
                Instant.now()));

        queue.stageAndQueue(assetId, source, Asset.Format.EPUB, Files.size(source));
        var claim = jobs.claim().orElseThrow();
        assertThat(Files.readString(jobs.leasedInput(claim.id(), claim.leaseToken())))
                .isEqualTo("cover queue fixture");
        var normalized = jpeg();
        jobs.complete(
                claim.id(),
                claim.leaseToken(),
                new CoverWorkerResult(
                        CoverWorkerResult.State.READY,
                        CoverWorkerResult.SelectionKind.DECLARED_EMBEDDED,
                        "OPS/cover.jpg",
                        sha256(normalized),
                        "yurlib-cover-worker",
                        "1",
                        60,
                        90,
                        "image/jpeg",
                        Base64.getEncoder().encodeToString(normalized),
                        null,
                        null));

        var location = covers.findByWorkId(catalogWorkId(assetId)).orElseThrow();
        assertThat(location.normalizedRelativePath()).matches("covers/[0-9a-f]{2}/[0-9a-f]{64}\\.jpg");
        assertThat(Files.readAllBytes(MANAGED.resolve(location.normalizedRelativePath())))
                .isEqualTo(normalized);
        assertThat(Files.exists(source)).isTrue();
        try (var staged = Files.list(STAGING)) {
            assertThat(staged).isEmpty();
        }
    }

    private UUID catalogWorkId(UUID assetId) {
        return jdbc.sql(
                        "SELECT edition.work_id FROM asset JOIN edition ON edition.id = asset.edition_id WHERE asset.id = :id")
                .param("id", assetId)
                .query(UUID.class)
                .single();
    }

    private static byte[] jpeg() throws Exception {
        var image = new BufferedImage(60, 90, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(21, 57, 92));
            graphics.fillRect(0, 0, 60, 90);
        } finally {
            graphics.dispose();
        }
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        }
    }

    private static Path mountDirectory() {
        var path = temporaryDirectory("yurlib-managed-covers-");
        try {
            Files.createDirectories(path.resolve("source"));
            Files.createDirectories(path.resolve("managed"));
            Files.writeString(path.resolve("source/.yurlib-root-id"), MARKER, StandardCharsets.UTF_8);
            Files.writeString(path.resolve("managed/.yurlib-root-id"), MARKER, StandardCharsets.UTF_8);
            return path;
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Path temporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
