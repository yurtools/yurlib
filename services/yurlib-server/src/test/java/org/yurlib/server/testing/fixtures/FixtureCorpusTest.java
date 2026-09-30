package org.yurlib.server.testing.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureCorpusTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void materializesEveryRequiredFixtureWithoutChangingItsSources() throws IOException {
        var before = FixtureCorpus.sourceHashes();
        var root = FixtureCorpus.materialize(temporaryDirectory.resolve("library"));

        assertThat(root.resolve("valid/minimal.fb2")).isRegularFile();
        assertThat(root.resolve("valid/日本語.fb2")).isRegularFile();
        assertThat(root.resolve("valid/кириллица.fb2"))
                .content(StandardCharsets.UTF_8)
                .contains("Кириллическая книга", "Анна", "Тестова", "<lang>ru</lang>");
        assertThat(root.resolve("malformed/broken.fb2")).isRegularFile();
        assertThat(root.resolve("security/xxe.fb2"))
                .content(StandardCharsets.UTF_8)
                .contains("<!ENTITY xxe SYSTEM");
        assertThat(root.resolve("valid/minimal.epub")).isRegularFile();
        assertThat(root.resolve("valid/minimal.mobi")).isRegularFile();
        assertThat(root.resolve("valid/updated-title.mobi")).isRegularFile();
        assertThat(root.resolve("security/traversal.epub")).isRegularFile();
        assertThat(root.resolve("security/decompression-limit.epub")).isRegularFile();
        assertThat(root.resolve("security/symlink-escape")).isSymbolicLink();
        var escapedTarget = root.resolve("security/symlink-escape").toRealPath();
        assertThat(escapedTarget).isRegularFile();
        assertThat(escapedTarget.startsWith(root.toRealPath())).isFalse();
        FixtureCorpus.assertSourcesUnchanged(before);
    }

    @Test
    void generatesEpubTraversalAndBoundedDecompressionCases() throws IOException {
        var root = FixtureCorpus.materialize(temporaryDirectory.resolve("library"));

        try (var traversal = new ZipFile(root.resolve("security/traversal.epub").toFile());
                var expansion = new ZipFile(
                        root.resolve("security/decompression-limit.epub").toFile())) {
            assertThat(traversal.getEntry("../escaped.txt")).isNotNull();
            var oversized = expansion.getEntry("OEBPS/oversized.txt");
            assertThat(oversized.getSize()).isEqualTo(2L * 1024 * 1024);
            assertThat(oversized.getSize()).isGreaterThan(oversized.getCompressedSize() * 100);
        }
    }

    @Test
    void generatesRecognizableEpubAndMobiSignatures() throws IOException {
        var root = FixtureCorpus.materialize(temporaryDirectory.resolve("library"));

        try (var epub = new ZipFile(root.resolve("valid/minimal.epub").toFile())) {
            assertThat(epub.getEntry("mimetype")).isNotNull();
            assertThat(epub.getEntry("META-INF/container.xml")).isNotNull();
            assertThat(epub.getEntry("OEBPS/content.opf")).isNotNull();
        }
        var mobi = Files.readAllBytes(root.resolve("valid/minimal.mobi"));
        assertThat(new String(mobi, 60, 8, StandardCharsets.US_ASCII)).isEqualTo("BOOKMOBI");
        assertThat(new String(mobi, 110, 4, StandardCharsets.US_ASCII)).isEqualTo("MOBI");
    }
}
