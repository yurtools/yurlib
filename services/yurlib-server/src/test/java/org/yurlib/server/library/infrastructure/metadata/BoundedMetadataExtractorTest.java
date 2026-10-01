package org.yurlib.server.library.infrastructure.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

        assertExtracted(epub, ExtractedBookMetadata.Format.EPUB, "Minimal EPUB Fixture", "jdk-epub", "3");
        assertThat(epub.metadata().contributors()).containsExactly("Fixture Author");
        assertThat(epub.metadata().language()).isEqualTo("en");
        assertThat(epub.metadata().identifiers()).containsValue("urn:uuid:yurlib-fixture");
        assertExtracted(fb2, ExtractedBookMetadata.Format.FB2, "Minimal FB2 Fixture", "jdk-fb2-stax", "2");
        assertThat(fb2.metadata().contributors()).containsExactly("Yurlib Fixture");
        assertThat(fb2.metadata().language()).isEqualTo("en");
        assertExtracted(mobi, ExtractedBookMetadata.Format.MOBI, "Кириллическая MOBI книга", "jdk-mobi-seek", "3");
        assertThat(mobi.metadata().contributors()).containsExactly("Анна Тестова");
        assertThat(mobi.metadata().language()).isEqualTo("ru");
        assertThat(mobi.metadata().identifiers())
                .containsEntry("isbn", "9780000000001")
                .containsEntry("asin", "B000YURLIB");
        assertThat(extractor.extractionVersion()).isEqualTo("bounded-metadata-v5");
    }

    @Test
    void extractsMultilingualDocxAndDjvuMetadataWithSourceSpecificObservations() throws IOException {
        var docxPath = library.resolve("valid/minimal.docx");
        var djvuPath = library.resolve("valid/minimal.djvu");
        FixtureCorpus.writeDocx(docxPath);
        FixtureCorpus.writeDjvu(djvuPath, "Многоязычный DjVu");

        var docx = extractor.extract(docxPath);
        var djvu = extractor.extract(djvuPath);

        assertExtracted(docx, ExtractedBookMetadata.Format.DOCX, "Многоязычный DOCX", "jdk-docx-opc", "1");
        assertThat(docx.metadata().contributors()).containsExactly("Анна Тестова");
        assertThat(docx.metadata().language()).isEqualTo("ru");
        assertThat(docx.metadata().identifiers()).containsEntry("docx-core", "urn:yurlib:docx-fixture");
        assertThat(docx.metadata().additionalObservations())
                .containsEntry("docx:core:title", List.of("Многоязычный DOCX"))
                .containsEntry("docx:custom", List.of("Collection=Generated fixtures"));
        assertExtracted(djvu, ExtractedBookMetadata.Format.DJVU, "Многоязычный DjVu", "jdk-djvu-iff", "2");
        assertThat(djvu.metadata().contributors()).containsExactly("Анна Тестова");
        assertThat(djvu.metadata().language()).isEqualTo("ru");
        assertThat(djvu.metadata().identifiers()).containsEntry("isbn", "9780000000002");
        assertThat(djvu.metadata().additionalObservations())
                .containsEntry("djvu:page-count", List.of("1"))
                .containsEntry("djvu:first-page-dpi", List.of("300"));
        assertThat(extractor.extractionVersion()).isEqualTo("bounded-metadata-v5");
    }

    @Test
    void rejectsEncryptedActiveAndExternallyRelatedDocxPackagesSafely() throws IOException {
        var encrypted = library.resolve("security/encrypted.docx");
        var macro = library.resolve("security/macro.docx");
        var external = library.resolve("security/external-relationship.docx");
        FixtureCorpus.writeEncryptedDocx(encrypted);
        FixtureCorpus.writeMacroDocx(macro);
        FixtureCorpus.writeDocxWithExternalRelationship(external);

        assertFailure(extractor.extract(encrypted), MetadataExtractionResult.ErrorCode.ENCRYPTED_ASSET);
        assertFailure(extractor.extract(macro), MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT);
        var externalResult = extractor.extract(external);
        assertFailure(externalResult, MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT);
        assertThat(externalResult.safeDiagnostic()).doesNotContain("example.invalid");
    }

    @Test
    void rejectsMalformedDocxAndDjvuContainersSafely() throws IOException {
        var docx = library.resolve("malformed/broken.docx");
        var djvu = library.resolve("malformed/broken.djvu");
        Files.createDirectories(docx.getParent());
        Files.writeString(docx, "not a ZIP package", StandardCharsets.US_ASCII);
        Files.write(djvu, "AT&TFORM".getBytes(StandardCharsets.US_ASCII));

        assertFailure(extractor.extract(docx), MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
        assertFailure(extractor.extract(djvu), MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
    }

    @Test
    void acceptsBoundedSharedDjvuFormsAndTerminalChunksWithoutPadding() throws IOException {
        var path = library.resolve("valid/shared-form.djvu");
        FixtureCorpus.writeDjvuWithSharedForm(path, "Shared DjVu");

        var result = extractor.extractMeasured(path);

        assertExtracted(result.result(), ExtractedBookMetadata.Format.DJVU, "Shared DjVu", "jdk-djvu-iff", "2");
        assertThat(result.result().metadata().additionalObservations()).containsEntry("djvu:page-count", List.of("1"));
        assertThat(result.usage().bytesRead()).isLessThan(1024);
    }

    @Test
    void rejectsMissingInteriorDjvuPaddingAndTruncatedTopLevelLength() throws IOException {
        var missingPadding = library.resolve("malformed/missing-interior-padding.djvu");
        var truncated = library.resolve("malformed/truncated-top-level.djvu");
        FixtureCorpus.writeDjvuWithMissingInteriorPadding(missingPadding, "Missing padding");
        FixtureCorpus.writeTruncatedDjvu(truncated, "Truncated");

        assertFailure(extractor.extract(missingPadding), MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
        assertFailure(extractor.extract(truncated), MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
    }

    @Test
    void skipsLargeUnselectedDjvuImageChunkWithBoundedReads() throws IOException {
        var path = FixtureCorpus.writeSparseDjvu(
                library.resolve("valid/large-sparse.djvu"), "Sparse DjVu", 96 * 1024 * 1024);

        var result = extractor.extractMeasured(path);

        assertExtracted(result.result(), ExtractedBookMetadata.Format.DJVU, "Sparse DjVu", "jdk-djvu-iff", "2");
        assertThat(result.usage().bytesRead()).isLessThan(128 * 1024);
        assertThat(result.usage().largestControlledBufferBytes()).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void enforcesSelectedValueBoundaryForDocxAndDjvu() throws IOException {
        var docxNear = library.resolve("valid/near-limit.docx");
        var docxOver = library.resolve("security/over-limit.docx");
        var djvuNear = library.resolve("valid/near-limit.djvu");
        var djvuOver = library.resolve("security/over-limit.djvu");
        FixtureCorpus.writeDocxWithTitle(docxNear, "D".repeat(64 * 1024));
        FixtureCorpus.writeDocxWithTitle(docxOver, "D".repeat(64 * 1024 + 1));
        FixtureCorpus.writeDjvu(djvuNear, "J".repeat(64 * 1024));
        FixtureCorpus.writeDjvu(djvuOver, "J".repeat(64 * 1024 + 1));

        assertThat(extractor.extract(docxNear).state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertFailure(extractor.extract(docxOver), MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
        assertThat(extractor.extract(djvuNear).state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertFailure(extractor.extract(djvuOver), MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
    }

    @Test
    void prefersTheExthUpdatedTitleToTheMobiFullName() {
        var result = extractor.extract(library.resolve("valid/updated-title.mobi"));

        assertExtracted(result, ExtractedBookMetadata.Format.MOBI, "Updated title", "jdk-mobi-seek", "3");
        assertThat(result.metadata().contributors()).containsExactly("Анна Тестова");
        assertThat(result.metadata().language()).isEqualTo("ru");
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

        assertExtracted(result, ExtractedBookMetadata.Format.FB2, "Кириллическая книга", "jdk-fb2-stax", "2");
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
        assertThat(expansion.safeDiagnostic()).contains("selected-entry");
    }

    @Test
    void acceptsExplicitEpubDirectoryEntries() throws IOException {
        var path = library.resolve("valid/explicit-directories.epub");
        FixtureCorpus.writeEpubWithDirectoryEntries(path);

        var result = extractor.extract(path);

        assertExtracted(result, ExtractedBookMetadata.Format.EPUB, "Minimal EPUB Fixture", "jdk-epub", "3");
    }

    @Test
    void retainsStrictEpubPathAndCanonicalNameRejection() throws IOException {
        var unsafeNames =
                List.of("/absolute-entry", "OEBPS\\backslash-entry", "OEBPS//empty-segment", "OEBPS/./dot-segment");
        for (var index = 0; index < unsafeNames.size(); index++) {
            var path = library.resolve("security/unsafe-entry-" + index + ".epub");
            FixtureCorpus.writeEpubWithUnsafeEntry(path, unsafeNames.get(index));
            assertFailure(extractor.extract(path), MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
        }
        var collision = library.resolve("security/canonical-name-collision.epub");
        FixtureCorpus.writeEpubWithCanonicalNameCollision(collision);
        assertFailure(extractor.extract(collision), MetadataExtractionResult.ErrorCode.CORRUPT_ASSET);
    }

    @Test
    void importsEpubWithLargeUnparsedImageWithinOperationRelevantBudgets() throws IOException {
        var largeImage = library.resolve("valid/large-image.epub");
        FixtureCorpus.writeEpubWithLargeUnparsedEntry(largeImage);

        var result = extractor.extractMeasured(largeImage);

        assertExtracted(result.result(), ExtractedBookMetadata.Format.EPUB, "Minimal EPUB Fixture", "jdk-epub", "3");
        assertThat(result.usage().bytesRead()).isLessThan(1024 * 1024);
        assertThat(result.usage().largestControlledBufferBytes()).isLessThanOrEqualTo(4 * 1024 * 1024 + 1);
        assertThat(result.usage().peakOpenFiles()).isLessThanOrEqualTo(2);
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

    @Test
    void streamsLargeFb2WithoutReadingEmbeddedBinaryPayload() throws IOException {
        var largeFb2 = library.resolve("valid/large-binary.fb2");
        FixtureCorpus.writeLargeFb2(largeFb2, 96 * 1024 * 1024);

        var result = extractor.extractMeasured(largeFb2);

        assertExtracted(result.result(), ExtractedBookMetadata.Format.FB2, "Large streaming FB2", "jdk-fb2-stax", "2");
        assertThat(result.usage().bytesRead()).isLessThan(1024 * 1024);
        assertThat(result.usage().largestControlledBufferBytes()).isLessThan(128 * 1024);
    }

    @Test
    void seeksOnlyRequiredMobiMetadataInLargeSparseSource() throws IOException {
        var largeMobi = FixtureCorpus.writeSparseMobi(
                library.resolve("valid/minimal.mobi"), library.resolve("valid/large-sparse.mobi"), 256L * 1024 * 1024);

        var result = extractor.extractMeasured(largeMobi);

        assertExtracted(
                result.result(), ExtractedBookMetadata.Format.MOBI, "Кириллическая MOBI книга", "jdk-mobi-seek", "3");
        assertThat(result.usage().bytesRead()).isLessThan(128 * 1024);
        assertThat(result.usage().largestControlledBufferBytes()).isLessThanOrEqualTo(32 * 1024);
    }

    @Test
    void acceptsSelectedScalarAtLimitAndRejectsOneByteOverWithSafeDiagnostic() throws IOException {
        var nearLimit = library.resolve("valid/near-limit.fb2");
        var overLimit = library.resolve("security/over-limit.fb2");
        writeFb2WithTitle(nearLimit, "N".repeat(64 * 1024));
        writeFb2WithTitle(overLimit, "O".repeat(64 * 1024 + 1));

        var accepted = extractor.extract(nearLimit);
        var rejected = extractor.extract(overLimit);

        assertThat(accepted.state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertFailure(rejected, MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
        assertThat(rejected.safeDiagnostic())
                .isEqualTo("Metadata extraction exceeded the selected-value limit (65536 bytes).");
    }

    @Test
    void acceptsFb2MetadataPrefixAtLimitAndRejectsOneByteOverWithoutChangingEitherSource() throws IOException {
        var nearLimit = library.resolve("valid/near-xml-limit.fb2");
        var overLimit = library.resolve("security/over-xml-limit.fb2");
        writeFb2WithDescriptionEndAt(nearLimit, 4 * 1024 * 1024);
        writeFb2WithDescriptionEndAt(overLimit, 4 * 1024 * 1024 + 1);
        var nearBytes = Files.readAllBytes(nearLimit);
        var overBytes = Files.readAllBytes(overLimit);

        var accepted = extractor.extractMeasured(nearLimit);
        var rejected = extractor.extract(overLimit);

        assertThat(accepted.result().state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertThat(accepted.usage().bytesRead()).isEqualTo(4L * 1024 * 1024);
        assertFailure(rejected, MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
        assertThat(rejected.safeDiagnostic())
                .isEqualTo("Metadata extraction exceeded the xml-metadata limit (4194304 bytes).");
        assertThat(Files.readAllBytes(nearLimit)).isEqualTo(nearBytes);
        assertThat(Files.readAllBytes(overLimit)).isEqualTo(overBytes);
    }

    @Test
    void acceptsNearLimitEpubMetadataAndMobiSelectedText() throws IOException {
        var nearLimitEpub = library.resolve("valid/near-limit.epub");
        var nearLimitMobi = library.resolve("valid/near-limit.mobi");
        FixtureCorpus.writeNearLimitEpub(nearLimitEpub);
        FixtureCorpus.writeMobiWithFullName(nearLimitMobi, "M".repeat(64 * 1024));

        var epub = extractor.extractMeasured(nearLimitEpub);
        var mobi = extractor.extractMeasured(nearLimitMobi);

        assertThat(epub.result().state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertThat(epub.usage().bytesRead()).isBetween(4L * 1024 * 1024, 5L * 1024 * 1024);
        assertThat(mobi.result().state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertThat(mobi.result().metadata().title()).hasSize(64 * 1024);
        assertThat(mobi.usage().largestControlledBufferBytes()).isEqualTo(64 * 1024);
    }

    @Test
    void rejectsMobiSelectedTextOneByteOverLimit() throws IOException {
        var overLimitMobi = library.resolve("security/over-limit.mobi");
        FixtureCorpus.writeMobiWithFullName(overLimitMobi, "M".repeat(64 * 1024 + 1));

        var result = extractor.extract(overLimitMobi);

        assertFailure(result, MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
        assertThat(result.safeDiagnostic())
                .isEqualTo("Metadata extraction exceeded the selected-value limit (65536 bytes).");
    }

    @Test
    void distinguishesTheAggregateReadBudgetFromXmlAndScalarLimits() {
        var limits = new MetadataResourceLimits(
                4L * 1024 * 1024 * 1024,
                128,
                4 * 1024 * 1024,
                10_000,
                4 * 1024 * 1024,
                64 * 1024,
                128,
                4,
                Duration.ofSeconds(30));
        var constrained = new BoundedMetadataExtractor(
                FileFactsReader.nio(),
                List.of(new EpubMetadataParser(), new Fb2MetadataParser(), new MobiMetadataParser()),
                limits);

        var result = constrained.extract(library.resolve("valid/minimal.fb2"));

        assertFailure(result, MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
        assertThat(result.safeDiagnostic()).isEqualTo("Metadata extraction exceeded the bytes-read limit (128 bytes).");
    }

    @Test
    void preservesEveryMaterializedInputOnSuccessAndFailure() throws IOException {
        var inputs = List.of(
                library.resolve("valid/minimal.epub"),
                library.resolve("valid/minimal.fb2"),
                library.resolve("valid/minimal.mobi"),
                library.resolve("malformed/broken.fb2"),
                library.resolve("security/xxe.fb2"),
                library.resolve("security/traversal.epub"),
                library.resolve("security/decompression-limit.epub"));
        var snapshots = inputs.stream().collect(java.util.stream.Collectors.toMap(path -> path, path -> {
            try {
                return Files.readAllBytes(path);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }));

        inputs.forEach(extractor::extract);

        for (var input : inputs) {
            assertThat(Files.readAllBytes(input)).isEqualTo(snapshots.get(input));
        }
    }

    private static void assertExtracted(
            MetadataExtractionResult result,
            ExtractedBookMetadata.Format format,
            String title,
            String parserName,
            String parserVersion) {
        assertThat(result.state()).isEqualTo(MetadataExtractionResult.State.EXTRACTED);
        assertThat(result.metadata().format()).isEqualTo(format);
        assertThat(result.metadata().title()).isEqualTo(title);
        assertThat(result.metadata().byteSize()).isPositive();
        assertThat(result.metadata().modifiedAt()).isNotNull();
        assertThat(result.metadata().parserName()).isEqualTo(parserName);
        assertThat(result.metadata().parserVersion()).isEqualTo(parserVersion);
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

    private static void writeFb2WithTitle(Path path, String title) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                        + "<description><title-info><book-title>"
                        + title
                        + "</book-title><lang>en</lang></title-info></description>"
                        + "</FictionBook>",
                StandardCharsets.UTF_8);
    }

    private static void writeFb2WithDescriptionEndAt(Path path, int targetBytes) throws IOException {
        var prefix = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><book-title>Bounded FB2</book-title><lang>en</lang></title-info><!--";
        var suffix = "--></description>";
        var padding = targetBytes
                - prefix.getBytes(StandardCharsets.UTF_8).length
                - suffix.getBytes(StandardCharsets.UTF_8).length;
        if (padding < 0) {
            throw new IllegalArgumentException("Target is smaller than the FB2 metadata wrapper.");
        }
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                prefix + "N".repeat(padding) + suffix + "<body/><binary>UNREAD</binary></FictionBook>",
                StandardCharsets.UTF_8);
    }

    private static final class StubMetadataParser implements MetadataParser {

        @Override
        public ExtractedBookMetadata.Format format() {
            return ExtractedBookMetadata.Format.FB2;
        }

        @Override
        public ParsedBookMetadata parse(Path file, MetadataResourceBudget budget) {
            return new ParsedBookMetadata(format(), "Observed title", List.of(), null, Map.of(), "stub-parser", "1");
        }
    }
}
