package org.yurlib.server.library.infrastructure.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.MetadataExtractionResult;
import org.yurlib.server.testing.fixtures.FixtureCorpus;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "YURLIB_RUN_INGESTION_BENCHMARK", matches = "true")
@SuppressWarnings("SystemOut")
class IngestionReferenceBenchmarkTest {

    private static final int DATABASE_SAMPLES = 50;
    private static final int API_SAMPLES = 50;
    private static final int MAXIMUM_FILES = 1_000;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @TempDir
    private Path temporaryDirectory;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsLocalAndConfiguredNetworkStorageObservations() throws Exception {
        var generatedLocal = FixtureCorpus.materialize(temporaryDirectory.resolve("local-ssd"));
        benchmark("local-ssd", pathFromEnvironment("YURLIB_BENCHMARK_LOCAL", generatedLocal.resolve("valid")));
        benchmarkOptional("smb", "YURLIB_BENCHMARK_SMB");
        benchmarkOptional("nfs", "YURLIB_BENCHMARK_NFS");
    }

    private void benchmarkOptional(String storage, String environmentName) throws Exception {
        var value = System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            System.out.printf("INGESTION_BENCHMARK|storage=%s|status=not_configured%n", storage);
            return;
        }
        benchmark(storage, Path.of(value));
    }

    private void benchmark(String storage, Path root) throws Exception {
        var files = supportedFiles(root);
        assertThat(files).as("supported files under %s", root).isNotEmpty();
        var extractor = new BoundedMetadataExtractor();

        resetHeapPeaks();
        var coldStarted = System.nanoTime();
        extractor.extractMeasured(files.getFirst());
        var timeToFirstMillis = nanosToMillis(System.nanoTime() - coldStarted);

        var warmStarted = System.nanoTime();
        List<BoundedMetadataExtractor.MeasuredExtraction> results;
        try (var executor = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory())) {
            List<Callable<BoundedMetadataExtractor.MeasuredExtraction>> operations = files.stream()
                    .<Callable<BoundedMetadataExtractor.MeasuredExtraction>>map(
                            path -> () -> extractor.extractMeasured(path))
                    .toList();
            results = new ArrayList<>();
            for (var future : executor.invokeAll(operations)) {
                results.add(future.get());
            }
        }
        var warmNanos = System.nanoTime() - warmStarted;
        var interactive = measureInteractiveWhileIngesting(extractor, files);
        var bytesRead =
                results.stream().mapToLong(result -> result.usage().bytesRead()).sum();
        var extracted = outcomeCount(results, MetadataExtractionResult.State.EXTRACTED);
        var deferred = outcomeCount(results, MetadataExtractionResult.State.DEFERRED);
        var failed = outcomeCount(results, MetadataExtractionResult.State.FAILED);
        reportOutcomeBreakdown(storage, files, results);
        var peakOpenFiles = results.stream()
                .mapToInt(result -> result.usage().peakOpenFiles())
                .max()
                .orElse(0);
        var filesPerSecond = files.size() * 1_000_000_000.0 / warmNanos;
        System.out.printf(
                Locale.ROOT,
                "INGESTION_BENCHMARK|storage=%s|status=observed|files=%d|extracted=%d|deferred=%d|failed=%d|time_to_first_ms=%.3f|warm_files_per_second=%.2f|counted_bytes=%d|peak_heap_bytes=%d|peak_open_files_per_task=%d|database_p95_us=%.3f|interactive_api_p95_us=%.3f%n",
                storage,
                files.size(),
                extracted,
                deferred,
                failed,
                timeToFirstMillis,
                filesPerSecond,
                bytesRead,
                peakHeapBytes(),
                peakOpenFiles,
                interactive.databaseP95Micros(),
                interactive.apiP95Micros());
    }

    private InteractiveP95 measureInteractiveWhileIngesting(BoundedMetadataExtractor extractor, List<Path> files)
            throws Exception {
        try (var executor = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory())) {
            var first = executor.submit(() -> ingestionLoad(extractor, files));
            var second = executor.submit(() -> ingestionLoad(extractor, files));
            var database = databaseP95Micros();
            var api = apiP95Micros();
            first.get();
            second.get();
            return new InteractiveP95(database, api);
        }
    }

    private static void ingestionLoad(BoundedMetadataExtractor extractor, List<Path> files) {
        for (var iteration = 0; iteration < 50; iteration++) {
            for (var file : files) {
                extractor.extract(file);
            }
        }
    }

    private static long outcomeCount(
            List<BoundedMetadataExtractor.MeasuredExtraction> results, MetadataExtractionResult.State state) {
        return results.stream()
                .filter(result -> result.result().state() == state)
                .count();
    }

    private static void reportOutcomeBreakdown(
            String storage, List<Path> files, List<BoundedMetadataExtractor.MeasuredExtraction> results) {
        var outcomes = new TreeMap<String, long[]>();
        var failures = new TreeMap<String, Long>();
        for (var index = 0; index < files.size(); index++) {
            var result = results.get(index).result();
            outcomes.computeIfAbsent(
                            extension(files.get(index)),
                            ignored -> new long[MetadataExtractionResult.State.values().length])[
                    result.state().ordinal()]++;
            if (result.state() == MetadataExtractionResult.State.FAILED) {
                failures.merge(result.errorCode().name(), 1L, Long::sum);
            }
        }
        outcomes.forEach((format, counts) -> System.out.printf(
                Locale.ROOT,
                "INGESTION_BENCHMARK_OUTCOMES|storage=%s|format=%s|extracted=%d|deferred=%d|failed=%d%n",
                storage,
                format,
                counts[MetadataExtractionResult.State.EXTRACTED.ordinal()],
                counts[MetadataExtractionResult.State.DEFERRED.ordinal()],
                counts[MetadataExtractionResult.State.FAILED.ordinal()]));
        failures.forEach((code, count) -> System.out.printf(
                Locale.ROOT, "INGESTION_BENCHMARK_FAILURES|storage=%s|code=%s|count=%d%n", storage, code, count));
    }

    private static String extension(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        var separator = name.lastIndexOf('.');
        return separator < 0 ? "none" : name.substring(separator + 1);
    }

    private double databaseP95Micros() {
        var jdbc = JdbcClient.create(dataSource);
        var samples = new ArrayList<Long>(DATABASE_SAMPLES);
        for (var index = 0; index < DATABASE_SAMPLES; index++) {
            var started = System.nanoTime();
            assertThat(jdbc.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
            samples.add(System.nanoTime() - started);
        }
        return nanosToMicros(percentile95(samples));
    }

    private double apiP95Micros() throws Exception {
        var samples = new ArrayList<Long>(API_SAMPLES);
        for (var index = 0; index < API_SAMPLES; index++) {
            var started = System.nanoTime();
            assertThat(mockMvc.perform(get("/actuator/health"))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(200);
            samples.add(System.nanoTime() - started);
        }
        return nanosToMicros(percentile95(samples));
    }

    private static List<Path> supportedFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(IngestionReferenceBenchmarkTest::supported)
                    .sorted()
                    .limit(MAXIMUM_FILES)
                    .toList();
        }
    }

    private static boolean supported(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".epub")
                || name.endsWith(".fb2")
                || name.endsWith(".mobi")
                || name.endsWith(".docx")
                || name.endsWith(".djvu")
                || name.endsWith(".djv");
    }

    private static Path pathFromEnvironment(String name, Path fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    private static long percentile95(List<Long> samples) {
        samples.sort(Comparator.naturalOrder());
        return samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double nanosToMicros(long nanos) {
        return nanos / 1_000.0;
    }

    private static void resetHeapPeaks() {
        heapPools().forEach(java.lang.management.MemoryPoolMXBean::resetPeakUsage);
    }

    private static long peakHeapBytes() {
        return heapPools().stream()
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    private static List<java.lang.management.MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();
    }

    private record InteractiveP95(double databaseP95Micros, double apiP95Micros) {}
}
