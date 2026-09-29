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

    private static final String CORRUPT_DIAGNOSTIC = "The book file could not be read safely.";

    private final FileFactsReader factsReader;
    private final Map<ExtractedBookMetadata.Format, MetadataParser> parsers;

    public BoundedMetadataExtractor() {
        this(
                FileFactsReader.nio(),
                List.of(new EpubMetadataParser(), new Fb2MetadataParser(), new MobiMetadataParser()));
    }

    BoundedMetadataExtractor(FileFactsReader factsReader, List<MetadataParser> parserList) {
        this.factsReader = factsReader;
        var configured = new EnumMap<ExtractedBookMetadata.Format, MetadataParser>(ExtractedBookMetadata.Format.class);
        for (var parser : parserList) {
            if (configured.put(parser.format(), parser) != null) {
                throw new IllegalArgumentException("Only one parser may be configured for each format.");
            }
        }
        parsers = Map.copyOf(configured);
    }

    @Override
    public MetadataExtractionResult extract(Path containedFile) {
        var format = format(containedFile);
        if (format == null || !parsers.containsKey(format)) {
            return MetadataExtractionResult.failed(
                    MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT,
                    "The file format is not supported for metadata extraction.");
        }

        var parser = parsers.get(format);
        try {
            var before = factsReader.read(containedFile);
            if (before.size() > parser.maximumSourceBytes()) {
                return MetadataExtractionResult.failed(
                        MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED,
                        "The book file exceeds the configured parsing limit.");
            }
            var parsed = parser.parse(containedFile);
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
        return null;
    }
}
