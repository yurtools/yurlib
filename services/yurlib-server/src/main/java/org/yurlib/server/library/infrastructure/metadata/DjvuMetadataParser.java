package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;

final class DjvuMetadataParser implements MetadataParser {

    private static final int FILE_HEADER_BYTES = 16;
    private static final int CHUNK_HEADER_BYTES = 8;

    @Override
    public ExtractedBookMetadata.Format format() {
        return ExtractedBookMetadata.Format.DJVU;
    }

    @Override
    public ParsedBookMetadata parse(Path file, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        try (var reader = BudgetedFileReader.open(file, budget)) {
            var size = reader.size();
            if (size < FILE_HEADER_BYTES) {
                throw corrupt("The DjVu container header is incomplete.");
            }
            var header = reader.read(0, FILE_HEADER_BYTES);
            if (!"AT&T".equals(ascii(header, 0, 4)) || !"FORM".equals(ascii(header, 4, 4))) {
                throw corrupt("The file does not contain a DjVu signature.");
            }
            var formLength = unsignedInt(header, 8);
            var formEnd = Math.addExact(12L, formLength);
            if (formLength < 4 || formEnd != size) {
                throw corrupt("The DjVu top-level form length is invalid.");
            }
            var formType = ascii(header, 12, 4);
            if (!"DJVU".equals(formType) && !"DJVM".equals(formType)) {
                throw unsupported("The DjVu form type is not supported.");
            }
            var state = new State("DJVU".equals(formType));
            scan(reader, 16, formEnd, 1, state, budget);
            if (state.pageCount() == 0) {
                throw corrupt("The DjVu document does not contain any pages.");
            }
            return state.metadata();
        } catch (ArithmeticException exception) {
            throw MetadataParsingException.limit("djvu-container-offset", Long.MAX_VALUE, "bytes", exception);
        }
    }

    private static void scan(
            BudgetedFileReader reader, long start, long end, int depth, State state, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        budget.checkDepth(depth);
        var position = start;
        while (position < end) {
            if (end - position < CHUNK_HEADER_BYTES) {
                throw corrupt("The DjVu container has a truncated chunk header.");
            }
            state.chunks++;
            if (state.chunks > budget.limits().maximumArchiveEntries()) {
                throw MetadataParsingException.limit(
                        "djvu-chunk-count", budget.limits().maximumArchiveEntries(), "chunks");
            }
            var header = reader.read(position, CHUNK_HEADER_BYTES);
            var id = ascii(header, 0, 4);
            var length = unsignedInt(header, 4);
            var dataStart = Math.addExact(position, CHUNK_HEADER_BYTES);
            var dataEnd = Math.addExact(dataStart, length);
            if (dataEnd > end) {
                throw corrupt("The DjVu container has an invalid chunk length.");
            }
            switch (id) {
                case "FORM" -> scanForm(reader, dataStart, dataEnd, depth, state, budget);
                case "INFO" -> readInfo(reader, dataStart, length, state, budget);
                case "DIRM" -> readDirectoryFacts(reader, dataStart, length, state, budget);
                case "ANTa" -> readAnnotations(reader, dataStart, length, state, budget);
                case "ANTz", "TXTa", "TXTz" -> state.compressedMetadataPresent = true;
                default -> {
                    // Image, palette, navigation, and unknown chunks are intentionally not decoded.
                }
            }
            if (dataEnd == end) {
                position = dataEnd;
            } else if ((length & 1L) != 0) {
                position = Math.addExact(dataEnd, 1);
            } else {
                position = dataEnd;
            }
            budget.checkpoint();
        }
        if (position != end) {
            throw corrupt("The DjVu container padding is invalid.");
        }
    }

    private static void scanForm(
            BudgetedFileReader reader,
            long dataStart,
            long dataEnd,
            int depth,
            State state,
            MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        if (dataEnd - dataStart < 4) {
            throw corrupt("The DjVu nested form is incomplete.");
        }
        var formType = ascii(reader.read(dataStart, 4), 0, 4);
        if ("DJVU".equals(formType)) {
            state.encounteredPages++;
            if (!state.pageMetadataScanned) {
                state.pageMetadataScanned = true;
                scan(reader, dataStart + 4, dataEnd, depth + 1, state, budget);
            }
            return;
        } else if ("DJVI".equals(formType)) {
            return;
        } else if (!"DJVM".equals(formType)) {
            throw unsupported("The DjVu document contains an unsupported nested form.");
        }
        scan(reader, dataStart + 4, dataEnd, depth + 1, state, budget);
    }

    private static void readInfo(
            BudgetedFileReader reader, long dataStart, long length, State state, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        if (length < 10) {
            throw corrupt("The DjVu page information chunk is incomplete.");
        }
        var info = reader.read(dataStart, 10);
        if (state.width == null) {
            state.width = Short.toUnsignedInt(info.getShort(0));
            state.height = Short.toUnsignedInt(info.getShort(2));
            state.dpi = Short.toUnsignedInt(info.getShort(6));
            if (state.width <= 0 || state.height <= 0 || state.dpi <= 0) {
                throw corrupt("The DjVu page information is invalid.");
            }
        }
        budget.checkpoint();
    }

    private static void readDirectoryFacts(
            BudgetedFileReader reader, long dataStart, long length, State state, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        if (length < 3) {
            throw corrupt("The DjVu directory chunk is incomplete.");
        }
        var prefix = reader.read(dataStart, 3);
        var declaredFiles = Short.toUnsignedInt(prefix.getShort(1));
        if (declaredFiles > budget.limits().maximumArchiveEntries()) {
            throw MetadataParsingException.limit(
                    "djvu-page-count", budget.limits().maximumArchiveEntries(), "pages");
        }
        state.declaredComponents = Math.max(state.declaredComponents, declaredFiles);
    }

    private static void readAnnotations(
            BudgetedFileReader reader, long dataStart, long length, State state, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        if (length > budget.limits().maximumXmlBytes()) {
            throw MetadataParsingException.limit(
                    "djvu-annotation", budget.limits().maximumXmlBytes(), "bytes");
        }
        var bytes = reader.read(dataStart, Math.toIntExact(length)).array();
        var text = strictUtf8(bytes);
        parseMetadataPairs(text, state, budget);
    }

    private static String strictUtf8(byte[] bytes) throws MetadataParsingException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new MetadataParsingException(
                    MetadataExtractionResult.ErrorCode.CORRUPT_ASSET,
                    "The DjVu annotation is not valid UTF-8.",
                    exception);
        }
    }

    private static void parseMetadataPairs(String text, State state, MetadataResourceBudget budget)
            throws MetadataParsingException {
        var cursor = 0;
        while (cursor < text.length()) {
            var opening = text.indexOf('(', cursor);
            if (opening < 0) {
                return;
            }
            var keyStart = skipWhitespace(text, opening + 1);
            var keyEnd = keyStart;
            while (keyEnd < text.length() && isKeyCharacter(text.charAt(keyEnd))) {
                keyEnd++;
            }
            var valueStart = skipWhitespace(text, keyEnd);
            if (keyEnd == keyStart || valueStart >= text.length() || text.charAt(valueStart) != '"') {
                cursor = opening + 1;
                continue;
            }
            var parsed = quoted(text, valueStart + 1, budget);
            if (parsed != null) {
                state.addMetadata(text.substring(keyStart, keyEnd), parsed.value());
                cursor = parsed.next();
            } else {
                return;
            }
        }
    }

    private static QuotedValue quoted(String text, int start, MetadataResourceBudget budget)
            throws MetadataParsingException {
        var value = new StringBuilder();
        var escaped = false;
        for (var index = start; index < text.length(); index++) {
            var character = text.charAt(index);
            if (escaped) {
                value.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                var normalized = SecureXml.normalizedSelected(value.toString(), budget);
                return normalized == null ? null : new QuotedValue(normalized, index + 1);
            } else {
                value.append(character);
            }
            if (value.length() > budget.limits().maximumSelectedValueBytes()) {
                throw MetadataParsingException.limit(
                        "selected-value", budget.limits().maximumSelectedValueBytes(), "bytes");
            }
            if ((value.length() & 0xff) == 0) {
                budget.checkSelectedValueBytes(value.toString().getBytes(StandardCharsets.UTF_8).length);
            }
        }
        return null;
    }

    private static int skipWhitespace(String text, int start) {
        var cursor = start;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isKeyCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '-' || value == '_';
    }

    private static String ascii(ByteBuffer buffer, int offset, int length) {
        return new String(buffer.array(), offset, length, StandardCharsets.US_ASCII);
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static MetadataParsingException corrupt(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message);
    }

    private static MetadataParsingException unsupported(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT, message);
    }

    private record QuotedValue(String value, int next) {}

    private static final class State {

        private final Map<String, List<String>> metadata = new LinkedHashMap<>();
        private int encounteredPages;
        private int declaredComponents;
        private int chunks;
        private Integer width;
        private Integer height;
        private Integer dpi;
        private boolean compressedMetadataPresent;
        private boolean pageMetadataScanned;

        private State(boolean standalonePage) {
            this.encounteredPages = standalonePage ? 1 : 0;
            this.pageMetadataScanned = standalonePage;
            this.width = null;
            this.height = null;
            this.dpi = null;
        }

        private void addMetadata(String key, String value) {
            metadata.computeIfAbsent(key.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(value);
        }

        private ParsedBookMetadata metadata() {
            var observations = new LinkedHashMap<String, List<String>>();
            observations.put("djvu:page-count", List.of(Integer.toString(pageCount())));
            if (declaredComponents > 0) {
                observations.put("djvu:component-count", List.of(Integer.toString(declaredComponents)));
            }
            if (width != null) {
                observations.put("djvu:first-page-width", List.of(width.toString()));
                observations.put("djvu:first-page-height", List.of(height.toString()));
                observations.put("djvu:first-page-dpi", List.of(dpi.toString()));
            }
            if (compressedMetadataPresent) {
                observations.put("djvu:compressed-metadata", List.of("present-not-decoded"));
            }
            metadata.forEach((key, values) -> observations.put("djvu:metadata:" + key, List.copyOf(values)));
            var title = first("title");
            var author = first("author");
            var language = first("language");
            var isbn = first("isbn");
            return new ParsedBookMetadata(
                    ExtractedBookMetadata.Format.DJVU,
                    title,
                    author == null ? List.of() : List.of(author),
                    language,
                    isbn == null ? Map.of() : Map.of("isbn", isbn),
                    observations,
                    "jdk-djvu-iff",
                    "2");
        }

        private String first(String key) {
            var values = metadata.get(key);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        private int pageCount() {
            return encounteredPages;
        }
    }
}
