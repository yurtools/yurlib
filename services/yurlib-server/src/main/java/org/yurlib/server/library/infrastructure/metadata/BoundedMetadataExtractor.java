package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;
import org.yurlib.server.library.application.MetadataExtractor;

public final class BoundedMetadataExtractor implements MetadataExtractor {

    public static final String EXTRACTION_VERSION = "bounded-metadata-v5";
    private static final String CORRUPT_DIAGNOSTIC = "The book file could not be read safely.";

    private final FileFactsReader factsReader;
    private final Map<ExtractedBookMetadata.Format, MetadataParser> parsers;
    private final MetadataResourceLimits limits;

    public BoundedMetadataExtractor() {
        this(
                FileFactsReader.nio(),
                List.of(
                        new EpubMetadataParser(),
                        new Fb2MetadataParser(),
                        new MobiMetadataParser(),
                        new DocxMetadataParser(),
                        new DjvuMetadataParser()),
                MetadataResourceLimits.m2Defaults());
    }

    BoundedMetadataExtractor(FileFactsReader factsReader, List<MetadataParser> parserList) {
        this(factsReader, parserList, MetadataResourceLimits.m2Defaults());
    }

    BoundedMetadataExtractor(
            FileFactsReader factsReader, List<MetadataParser> parserList, MetadataResourceLimits limits) {
        this.factsReader = factsReader;
        this.limits = limits;
        var configured = new EnumMap<ExtractedBookMetadata.Format, MetadataParser>(ExtractedBookMetadata.Format.class);
        for (var parser : parserList) {
            if (configured.put(parser.format(), parser) != null) {
                throw new IllegalArgumentException("Only one parser may be configured for each format.");
            }
        }
        parsers = Map.copyOf(configured);
    }

    @Override
    public String extractionVersion() {
        return EXTRACTION_VERSION;
    }

    @Override
    public MetadataExtractionResult extract(Path containedFile) {
        return extractMeasured(containedFile).result();
    }

    MeasuredExtraction extractMeasured(Path containedFile) {
        var budget = new MetadataResourceBudget(limits);
        return new MeasuredExtraction(extract(containedFile, budget), budget.usage());
    }

    private MetadataExtractionResult extract(Path containedFile, MetadataResourceBudget budget) {
        var format = format(containedFile);
        if (format == null || !parsers.containsKey(format)) {
            return MetadataExtractionResult.failed(
                    MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT,
                    "The file format is not supported for metadata extraction.");
        }

        var parser = parsers.get(format);
        try {
            var before = factsReader.read(containedFile);
            budget.checkSourceSize(before.size());
            var parsed = parser.parse(containedFile, budget);
            var after = factsReader.read(containedFile);
            if (!before.equals(after)) {
                return MetadataExtractionResult.deferred(
                        MetadataExtractionResult.ErrorCode.FILE_UNSTABLE,
                        "The book file changed during metadata extraction.");
            }
            return MetadataExtractionResult.extracted(new ExtractedBookMetadata(
                    parsed.format(),
                    parsed.title(),
                    parsed.contributors(),
                    parsed.language(),
                    parsed.identifiers(),
                    parsed.additionalObservations(),
                    before.size(),
                    before.modifiedAt(),
                    parsed.parserName(),
                    parsed.parserVersion()));
        } catch (MetadataParsingException failure) {
            return MetadataExtractionResult.failed(failure.code(), failure.getMessage());
        } catch (IOException | RuntimeException failure) {
            return MetadataExtractionResult.failed(
                    MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, CORRUPT_DIAGNOSTIC);
        }
    }

    record MeasuredExtraction(MetadataExtractionResult result, MetadataResourceBudget.Usage usage) {}

    private static ExtractedBookMetadata.Format format(Path file) {
        var fileName = file.getFileName();
        if (fileName == null) {
            return null;
        }
        var name = fileName.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".epub")) {
            return ExtractedBookMetadata.Format.EPUB;
        }
        if (name.endsWith(".fb2")) {
            return ExtractedBookMetadata.Format.FB2;
        }
        if (name.endsWith(".mobi")) {
            return ExtractedBookMetadata.Format.MOBI;
        }
        if (name.endsWith(".pdf")) {
            return ExtractedBookMetadata.Format.PDF;
        }
        if (name.endsWith(".docx")) {
            return ExtractedBookMetadata.Format.DOCX;
        }
        if (name.endsWith(".djvu") || name.endsWith(".djv")) {
            return ExtractedBookMetadata.Format.DJVU;
        }
        return null;
    }
}
