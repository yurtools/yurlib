package org.yurlib.server.library.infrastructure.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;
import org.yurlib.server.testing.fixtures.FixtureCorpus;

class BoundedMetadataExtractorTest {

    @TempDir
    private Path temporaryDirectory;

    private Path library;
    private BoundedMetadataExtractor extractor;

    @BeforeEach
    void setUp() throws IOException {
        library = FixtureCorpus.materialize(temporaryDirectory.resolve("library"));
        extractor = new BoundedMetadataExtractor();
    }

    @Test
    void extractsProvenanceBearingMetadataFromEverySupportedFormat() throws IOException {
        var epub = extractor.extract(library.resolve("valid/minimal.epub"));
        var fb2 = extractor.extract(library.resolve("valid/minimal.fb2"));
        var mobi = extractor.extract(library.resolve("valid/minimal.mobi"));

        assertExtracted(epub, ExtractedBookMetadata.Format.EPUB, "Minimal EPUB Fixture", "jdk-epub");
        assertThat(epub.metadata().contributors()).containsExactly("Fixture Author");
        assertThat(epub.metadata().language()).isEqualTo("en");
        assertThat(epub.metadata().identifiers()).containsValue("urn:uuid:yurlib-fixture");
        assertExtracted(fb2, ExtractedBookMetadata.Format.FB2, "Minimal FB2 Fixture", "jdk-fb2");
        assertThat(fb2.metadata().contributors()).containsExactly("Yurlib Fixture");
        assertThat(fb2.metadata().language()).isEqualTo("en");
        assertExtracted(mobi, ExtractedBookMetadata.Format.MOBI, "Yurlib fixture", "jdk-mobi");
        assertThat(mobi.metadata().contributors()).isEmpty();
        assertThat(mobi.metadata().language()).isNull();
        assertThat(mobi.metadata().identifiers()).isEmpty();
    }

    @Test
    void leavesAbsentMetadataAbsent() {
        var result = extractor.extract(library.resolve("valid/日本語.fb2"));

        assertThat(result.state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertThat(result.metadata().title()).isEqualTo("Unicode Path");
        assertThat(result.metadata().language()).isEqualTo("ja");
        assertThat(result.metadata().contributors()).isEmpty();
        assertThat(result.metadata().identifiers()).isEmpty();
    }

    @Test
    void preservesCyrillicFb2MetadataAndFilename() {
        var result = extractor.extract(library.resolve("valid/кириллица.fb2"));

        assertExtracted(result, ExtractedBookMetadata.Format.FB2, "Кириллическая книга", "jdk-fb2");
        assertThat(result.metadata().contributors()).containsExactly("Анна Тестова");
        assertThat(result.metadata().language()).isEqualTo("ru");
    }

    @Test
    void rejectsMalformedAndExternalEntityFb2WithoutResolvingTheEntity() {
        var malformed = extractor.extract(library.resolve("malformed/broken.fb2"));
        var externalEntity = extractor.extract(library.resolve("security/xxe.fb2"));

        assertFailure(malformed, MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
        assertFailure(externalEntity, MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
        assertThat(externalEntity.safeDiagnostic()).doesNotContain("/etc/passwd");
    }

    @Test
    void rejectsUnsafeAndExpandingEpubEntries() {
        var traversal = extractor.extract(library.resolve("security/traversal.epub"));
        var expansion = extractor.extract(library.resolve("security/decompression-limit.epub"));

        assertFailure(traversal, MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
        assertFailure(expansion, MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
    }

    @Test
    void distinguishesEncryptedEpubAndMobiFiles() throws IOException {
        var encryptedEpub =
                Files.copy(library.resolve("valid/minimal.epub"), library.resolve("security/encrypted.epub"));
        markFirstZipEntryEncrypted(encryptedEpub);
        var encryptedMobi =
                Files.copy(library.resolve("valid/minimal.mobi"), library.resolve("security/encrypted.mobi"));
        markMobiEncrypted(encryptedMobi);

        assertFailure(extractor.extract(encryptedEpub), MetadataExtractionResult.ErrorCode.ENCRYPTED_ASSET);
        assertFailure(extractor.extract(encryptedMobi), MetadataExtractionResult.ErrorCode.ENCRYPTED_ASSET);
    }

    @Test
    void distinguishesUnsupportedExtensionsAndMobiAllocationLimits() throws IOException {
        var zippedFb2 = Files.writeString(
                library.resolve("valid/book.fb2.zip"), "not a supported plain FB2", StandardCharsets.UTF_8);
        var excessiveRecords =
                Files.copy(library.resolve("valid/minimal.mobi"), library.resolve("security/excessive-records.mobi"));
        var bytes = Files.readAllBytes(excessiveRecords);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putShort(76, (short) 5000);
        Files.write(excessiveRecords, bytes);

        assertFailure(extractor.extract(zippedFb2), MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT);
        assertFailure(extractor.extract(excessiveRecords), MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
    }

    @Test
    void defersMetadataWhenFileFactsChangeDuringParsing() throws IOException {
        var calls = new AtomicInteger();
        var before = new FileFactsReader.FileFacts(10, Instant.parse("2026-09-29T12:00:00Z"), "file-key");
        var after = new FileFactsReader.FileFacts(11, Instant.parse("2026-09-29T12:00:01Z"), "file-key");
        FileFactsReader facts = ignored -> calls.getAndIncrement() == 0 ? before : after;
        MetadataParser parser = new StubMetadataParser();
        var unstableExtractor = new BoundedMetadataExtractor(facts, List.of(parser));

        var result = unstableExtractor.extract(library.resolve("valid/minimal.fb2"));

        assertThat(result.state()).isEqualTo(MetadataExtractionResult.State.DEFERRED);
        assertThat(result.errorCode()).isEqualTo(MetadataExtractionResult.ErrorCode.FILE_UNSTABLE);
        assertThat(result.metadata()).isNull();
    }

    @Test
    void doesNotModifyFixtureSourceBytes() throws IOException {
        var before = FixtureCorpus.sourceHashes();

        extractor.extract(library.resolve("valid/minimal.fb2"));
        extractor.extract(library.resolve("valid/minimal.epub"));
        extractor.extract(library.resolve("valid/minimal.mobi"));

        FixtureCorpus.assertSourcesUnchanged(before);
    }

    private static void assertExtracted(
            MetadataExtractionResult result, ExtractedBookMetadata.Format format, String title, String parserName) {
        assertThat(result.state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertThat(result.metadata().format()).isEqualTo(format);
        assertThat(result.metadata().title()).isEqualTo(title);
        assertThat(result.metadata().byteSize()).isPositive();
        assertThat(result.metadata().modifiedAt()).isNotNull();
        assertThat(result.metadata().parserName()).isEqualTo(parserName);
        assertThat(result.metadata().parserVersion()).isEqualTo("1");
    }

    private static void assertFailure(
            MetadataExtractionResult result, MetadataExtractionResult.ErrorCode expectedCode) {
        assertThat(result.state()).isEqualTo(MetadataExtractionResult.State.FAILED);
        assertThat(result.errorCode()).isEqualTo(expectedCode);
        assertThat(result.metadata()).isNull();
        assertThat(result.safeDiagnostic()).isNotBlank();
    }

    private static void markFirstZipEntryEncrypted(Path path) throws IOException {
        var bytes = Files.readAllBytes(path);
        for (var index = 0; index <= bytes.length - 10; index++) {
            if (bytes[index] == 0x50
                    && bytes[index + 1] == 0x4b
                    && bytes[index + 2] == 0x01
                    && bytes[index + 3] == 0x02) {
                bytes[index + 8] = (byte) (bytes[index + 8] | 1);
                Files.write(path, bytes);
                return;
            }
        }
        throw new IOException("Generated EPUB central directory was not found.");
    }

    private static void markMobiEncrypted(Path path) throws IOException {
        var bytes = Files.readAllBytes(path);
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        var firstRecordOffset = buffer.getInt(78);
        buffer.putShort(firstRecordOffset + 12, (short) 1);
        Files.write(path, bytes);
    }

    private static final class StubMetadataParser implements MetadataParser {

        @Override
        public ExtractedBookMetadata.Format format() {
            return ExtractedBookMetadata.Format.FB2;
        }

        @Override
        public long maximumSourceBytes() {
            return 1024;
        }

        @Override
        public ParsedBookMetadata parse(Path file) {
            return new ParsedBookMetadata(format(), "Observed title", List.of(), null, Map.of(), "stub-parser", "1");
        }
    }
}
