package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import org.yurlib.server.library.application.ConversionJob;
import org.yurlib.server.library.application.ConversionRoute;
import org.yurlib.server.library.application.ConversionUseCases;
import org.yurlib.server.library.application.ConversionWorkerJobService;
import org.yurlib.server.library.application.ConversionWorkerResult;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.domain.LibraryRoot;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class ConversionQueueIntegrationTest {

    private static final String MARKER = "conversion-output-marker-token";
    private static final Path MOUNT = mountDirectory();
    private static final Path MANAGED = MOUNT.resolve("managed");
    private static final Path STAGING = temporaryDirectory("yurlib-conversion-staging-");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("yurlib.conversion-worker.staging-directory", STAGING::toString);
        registry.add("yurlib.library.mounts[0].alias", () -> "conversion-test");
        registry.add("yurlib.library.mounts[0].path", MOUNT::toString);
    }

    @Autowired
    private LibraryRootStore roots;

    @Autowired
    private ScanJobStore scans;

    @Autowired
    private CatalogStore catalog;

    @Autowired
    private ConversionUseCases conversions;

    @Autowired
    private ConversionWorkerJobService workerJobs;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void publishesValidatedEpubWithLineageAndDeduplicatesTheRouteKey() throws Exception {
        var source = seedSource("fixture.fb2", ExtractedBookMetadata.Format.FB2);
        var requested = conversions.request(source.assetId(), ConversionRoute.FB2_TO_EPUB_V1);
        var duplicate = conversions.request(source.assetId(), ConversionRoute.FB2_TO_EPUB_V1);
        assertThat(duplicate.id()).isEqualTo(requested.id());

        var claim = workerJobs.claim().orElseThrow();
        assertThat(Files.readString(workerJobs.leasedInput(claim.id(), claim.leaseToken())))
                .contains("FictionBook");
        assertThat(workerJobs.heartbeat(claim.id(), claim.leaseToken())).isFalse();

        var output = Files.createTempFile("converted-", ".epub");
        writeEpub(output);
        var outputHash = sha256(Files.readAllBytes(output));
        try (var input = Files.newInputStream(output)) {
            workerJobs.upload(claim.id(), claim.leaseToken(), Files.size(output), outputHash, input);
        }
        workerJobs.complete(
                claim.id(),
                claim.leaseToken(),
                new ConversionWorkerResult(
                        ConversionWorkerResult.State.SUCCEEDED,
                        outputHash,
                        Files.size(output),
                        "calibre",
                        "9.15.0",
                        null,
                        null));

        var complete = conversions.find(requested.id());
        assertThat(complete.state()).isEqualTo(ConversionJob.State.SUCCEEDED);
        assertThat(complete.derivedAssetId()).isNotNull();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM asset_derivation_source
                        WHERE derived_asset_id = :derived AND source_asset_id = :source
                        """)
                        .param("derived", complete.derivedAssetId())
                        .param("source", source.assetId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        var relative = jdbc.sql("SELECT normalized_relative_path FROM asset_location WHERE asset_id = :id")
                .param("id", complete.derivedAssetId())
                .query(String.class)
                .single();
        assertThat(relative).matches("conversions/[0-9a-f]{2}/[0-9a-f]{64}\\.epub");
        assertThat(Files.readAllBytes(MANAGED.resolve(relative))).isEqualTo(Files.readAllBytes(output));
        assertThat(Files.readString(source.path())).contains("FictionBook");
    }

    @Test
    void cancelsAQueuedJobWithoutPublishingOutput() throws Exception {
        var source = seedSource("cancel.mobi", ExtractedBookMetadata.Format.MOBI);
        var requested = conversions.request(source.assetId(), ConversionRoute.MOBI_TO_EPUB_V1);

        var cancelled = conversions.cancel(requested.id(), requested.version());

        assertThat(cancelled.state()).isEqualTo(ConversionJob.State.CANCELLED);
        assertThat(workerJobs.claim()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM asset WHERE derivation = 'DERIVED'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void rejectsMalformedOutputWithoutPublishingIt() throws Exception {
        var source = seedSource("malformed-output.fb2", ExtractedBookMetadata.Format.FB2);
        var requested = conversions.request(source.assetId(), ConversionRoute.FB2_TO_EPUB_V1);
        var claim = workerJobs.claim().orElseThrow();
        var output = Files.createTempFile("malformed-conversion-", ".epub");
        Files.writeString(output, "not an EPUB", StandardCharsets.UTF_8);
        var outputHash = sha256(Files.readAllBytes(output));
        try (var input = Files.newInputStream(output)) {
            workerJobs.upload(claim.id(), claim.leaseToken(), Files.size(output), outputHash, input);
        }

        workerJobs.complete(
                claim.id(),
                claim.leaseToken(),
                new ConversionWorkerResult(
                        ConversionWorkerResult.State.SUCCEEDED,
                        outputHash,
                        Files.size(output),
                        "calibre",
                        "9.15.0",
                        null,
                        null));

        assertThat(conversions.find(requested.id()).state()).isEqualTo(ConversionJob.State.FAILED_SAFE);
        assertThat(derivedAssetCount()).isZero();
        assertThat(Files.readString(source.path())).contains("FictionBook");
    }

    @Test
    void failsAnExhaustedLeaseWithoutPublishingPartialOutput() throws Exception {
        var source = seedSource("crashed-worker.fb2", ExtractedBookMetadata.Format.FB2);
        var requested = conversions.request(source.assetId(), ConversionRoute.FB2_TO_EPUB_V1);
        var claim = workerJobs.claim().orElseThrow();
        jdbc.sql("""
                        UPDATE conversion_job
                        SET attempt_count = 3, lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id
                        """).param("id", claim.id()).update();

        assertThat(workerJobs.claim()).isEmpty();

        var failed = conversions.find(requested.id());
        assertThat(failed.state()).isEqualTo(ConversionJob.State.FAILED_SAFE);
        assertThat(failed.errorCode()).isEqualTo("CONVERSION_CRASHED");
        assertThat(derivedAssetCount()).isZero();
    }

    @Test
    void reclaimsAnExpiredLeaseAndDiscardsItsUnpublishedUpload() throws Exception {
        var source = seedSource("restarted-worker.fb2", ExtractedBookMetadata.Format.FB2);
        var requested = conversions.request(source.assetId(), ConversionRoute.FB2_TO_EPUB_V1);
        var firstClaim = workerJobs.claim().orElseThrow();
        var partial = Files.createTempFile("abandoned-conversion-", ".epub");
        Files.writeString(partial, "abandoned output", StandardCharsets.UTF_8);
        var partialHash = sha256(Files.readAllBytes(partial));
        try (var input = Files.newInputStream(partial)) {
            workerJobs.upload(firstClaim.id(), firstClaim.leaseToken(), Files.size(partial), partialHash, input);
        }
        jdbc.sql("""
                        UPDATE conversion_job
                        SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id
                        """).param("id", firstClaim.id()).update();

        var reclaimed = workerJobs.claim().orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(firstClaim.id());
        assertThat(reclaimed.leaseToken()).isNotEqualTo(firstClaim.leaseToken());
        var output = Files.createTempFile("reclaimed-conversion-", ".epub");
        writeEpub(output);
        var outputHash = sha256(Files.readAllBytes(output));
        try (var input = Files.newInputStream(output)) {
            workerJobs.upload(reclaimed.id(), reclaimed.leaseToken(), Files.size(output), outputHash, input);
        }
        workerJobs.complete(
                reclaimed.id(),
                reclaimed.leaseToken(),
                new ConversionWorkerResult(
                        ConversionWorkerResult.State.SUCCEEDED,
                        outputHash,
                        Files.size(output),
                        "calibre",
                        "9.15.0",
                        null,
                        null));

        assertThat(conversions.find(requested.id()).state()).isEqualTo(ConversionJob.State.SUCCEEDED);
        assertThat(derivedAssetCount()).isEqualTo(1);
    }

    @Test
    void acceptsBoundedWorkerFailureClassificationsWithoutPublishing() throws Exception {
        for (var failureCode : List.of("CONVERSION_TIMEOUT", "RESOURCE_LIMIT")) {
            var source = seedSource(failureCode.toLowerCase(Locale.ROOT) + ".fb2", ExtractedBookMetadata.Format.FB2);
            var requested = conversions.request(source.assetId(), ConversionRoute.FB2_TO_EPUB_V1);
            var claim = workerJobs.claim().orElseThrow();

            workerJobs.complete(
                    claim.id(),
                    claim.leaseToken(),
                    new ConversionWorkerResult(
                            ConversionWorkerResult.State.FAILED_SAFE,
                            null,
                            null,
                            "calibre",
                            "9.15.0",
                            failureCode,
                            "The isolated converter failed within its resource boundary."));

            var failed = conversions.find(requested.id());
            assertThat(failed.state()).isEqualTo(ConversionJob.State.FAILED_SAFE);
            assertThat(failed.errorCode()).isEqualTo(failureCode);
        }
        assertThat(derivedAssetCount()).isZero();
    }

    private long derivedAssetCount() {
        return jdbc.sql("SELECT count(*) FROM asset WHERE derivation = 'DERIVED'")
                .query(Long.class)
                .single();
    }

    private Source seedSource(String name, ExtractedBookMetadata.Format format) throws Exception {
        var relativeRoot = "source-" + UUID.randomUUID();
        var rootPath = MOUNT.resolve(relativeRoot);
        Files.createDirectory(rootPath);
        Files.writeString(rootPath.resolve(".yurlib-root-id"), MARKER, StandardCharsets.UTF_8);
        var sourceRoot = roots.save(new LibraryRoot(
                UUID.randomUUID(),
                "Source " + name,
                "conversion-test",
                relativeRoot,
                sha256(MARKER.getBytes(StandardCharsets.UTF_8)),
                LibraryRoot.Mode.READ_ONLY_SOURCE,
                LibraryRoot.Availability.AVAILABLE,
                null));
        if (roots.findAll().stream().noneMatch(LibraryRoot::defaultForConversions)) {
            roots.save(new LibraryRoot(
                    UUID.randomUUID(),
                    "Managed conversions",
                    "conversion-test",
                    "managed",
                    sha256(MARKER.getBytes(StandardCharsets.UTF_8)),
                    LibraryRoot.Mode.MANAGED_OUTPUT,
                    LibraryRoot.Availability.AVAILABLE,
                    null,
                    false,
                    true));
        }
        var scan = scans.queue(sourceRoot.id(), "conversion-test", "conversion-test", Instant.now());
        var path = rootPath.resolve(name);
        if (format == ExtractedBookMetadata.Format.FB2) {
            Files.writeString(path, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                      <description><title-info><book-title>Conversion fixture %s</book-title><lang>en</lang></title-info></description>
                      <body><section><p>Fixture body.</p></section></body>
                    </FictionBook>
                    """.formatted(name));
        } else {
            Files.write(path, "synthetic-mobi-source".getBytes(StandardCharsets.US_ASCII));
        }
        var assetId = catalog.reconcile(new CatalogReconciliation(
                sourceRoot.id(),
                scan.id(),
                name,
                null,
                "conversion-test",
                new ExtractedBookMetadata(
                        format,
                        "Conversion fixture",
                        List.of(),
                        "en",
                        Map.of(),
                        Map.of(),
                        Files.size(path),
                        Files.getLastModifiedTime(path).toInstant(),
                        "fixture",
                        "1"),
                Instant.now()));
        return new Source(assetId, path);
    }

    private static void writeEpub(Path path) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            var mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            var entry = new ZipEntry("mimetype");
            var crc = new CRC32();
            crc.update(mimetype);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(mimetype.length);
            entry.setCompressedSize(mimetype.length);
            entry.setCrc(crc.getValue());
            output.putNextEntry(entry);
            output.write(mimetype);
            output.closeEntry();
            writeEntry(output, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            writeEntry(output, "OPS/content.opf", "<package version=\"3.0\"></package>");
        }
    }

    private static void writeEntry(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static Path mountDirectory() {
        var path = temporaryDirectory("yurlib-managed-conversions-");
        try {
            Files.createDirectories(path.resolve("source"));
            Files.createDirectories(path.resolve("managed"));
            Files.writeString(path.resolve("source/.yurlib-root-id"), MARKER, StandardCharsets.UTF_8);
            Files.writeString(path.resolve("managed/.yurlib-root-id"), MARKER, StandardCharsets.UTF_8);
            return path;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Path temporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Source(UUID assetId, Path path) {}
}
