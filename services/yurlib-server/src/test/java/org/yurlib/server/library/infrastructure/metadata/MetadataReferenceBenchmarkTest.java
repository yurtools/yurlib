package org.yurlib.server.library.infrastructure.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yurlib.server.library.application.MetadataExtractionResult;
import org.yurlib.server.testing.fixtures.FixtureCorpus;

@SuppressWarnings("SystemOut")
class MetadataReferenceBenchmarkTest {

    private static final int ITERATIONS = 12;

    @TempDir
    private Path temporaryDirectory;

    @Test
    void reportsReadAllocationMemoryAndThroughputSeparately() throws IOException {
        var library = FixtureCorpus.materialize(temporaryDirectory.resolve("library"));
        var largeFb2 = library.resolve("valid/benchmark-large.fb2");
        var largeMobi = library.resolve("valid/benchmark-large.mobi");
        var largeEpub = library.resolve("valid/benchmark-large.epub");
        FixtureCorpus.writeEpubWithLargeUnparsedEntry(largeEpub);
        FixtureCorpus.writeLargeFb2(largeFb2, 96 * 1024 * 1024);
        FixtureCorpus.writeSparseMobi(library.resolve("valid/minimal.mobi"), largeMobi, 256L * 1024 * 1024);
        var extractor = new BoundedMetadataExtractor();

        var cases = List.of(
                new BenchmarkCase("EPUB-large-unparsed-entry", largeEpub),
                new BenchmarkCase("FB2-96MiB-binary", largeFb2),
                new BenchmarkCase("MOBI-256MiB-sparse", largeMobi));
        for (var benchmark : cases) {
            extractor.extract(benchmark.path());
            var result = measure(extractor, benchmark);
            assertThat(result.successes()).isEqualTo(ITERATIONS);
            assertThat(result.averageBytesRead()).isLessThan(64L * 1024 * 1024);
            System.out.printf(
                    Locale.ROOT,
                    "METADATA_BENCHMARK|%s|source_bytes=%d|avg_bytes_read=%d|largest_controlled_buffer=%d|peak_heap_bytes=%d|files_per_second=%.2f%n",
                    benchmark.name(),
                    result.sourceBytes(),
                    result.averageBytesRead(),
                    result.largestControlledBuffer(),
                    result.peakHeapBytes(),
                    result.filesPerSecond());
        }
    }

    private static BenchmarkResult measure(BoundedMetadataExtractor extractor, BenchmarkCase benchmark)
            throws IOException {
        resetHeapPeaks();
        long bytesRead = 0;
        var largestBuffer = 0;
        var successes = 0;
        var started = System.nanoTime();
        for (var index = 0; index < ITERATIONS; index++) {
            var measured = extractor.extractMeasured(benchmark.path());
            if (measured.result().state() == MetadataExtractionResult.State.EXTRACTED) {
                successes++;
            }
            bytesRead += measured.usage().bytesRead();
            largestBuffer = Math.max(largestBuffer, measured.usage().largestControlledBufferBytes());
        }
        var elapsedNanos = System.nanoTime() - started;
        return new BenchmarkResult(
                Files.size(benchmark.path()),
                bytesRead / ITERATIONS,
                largestBuffer,
                peakHeapBytes(),
                ITERATIONS * 1_000_000_000.0 / elapsedNanos,
                successes);
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

    private record BenchmarkCase(String name, Path path) {}

    private record BenchmarkResult(
            long sourceBytes,
            long averageBytesRead,
            int largestControlledBuffer,
            long peakHeapBytes,
            double filesPerSecond,
            int successes) {}
}
