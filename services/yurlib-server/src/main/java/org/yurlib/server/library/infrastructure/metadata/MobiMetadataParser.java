package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;

final class MobiMetadataParser implements MetadataParser {

    private static final int PDB_HEADER_BYTES = 78;
    private static final int MAXIMUM_RECORDS = 4096;
    private static final int MAXIMUM_RECORD_ZERO_BYTES = 1024 * 1024;
    private static final int MAXIMUM_EXTH_BYTES = 1024 * 1024;
    private static final int MAXIMUM_EXTH_RECORDS = 1024;
    private static final int MOBI_HEADER_START = 16;
    private static final int FULL_NAME_OFFSET_FIELD = 0x44;
    private static final int FULL_NAME_LENGTH_FIELD = 0x48;
    private static final int EXTH_FLAGS_FIELD = 0x70;
    private static final int REQUIRED_MOBI_PREFIX_BYTES = MOBI_HEADER_START + EXTH_FLAGS_FIELD + Integer.BYTES;

    @Override
    public ExtractedBookMetadata.Format format() {
        return ExtractedBookMetadata.Format.MOBI;
    }

    @Override
    public ParsedBookMetadata parse(Path file, MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        try (var reader = BudgetedFileReader.open(file, budget)) {
            var fileSize = reader.size();
            var header = reader.read(0, PDB_HEADER_BYTES);
            requireSignature(header);
            var recordCount = Short.toUnsignedInt(header.getShort(76));
            if (recordCount == 0 || recordCount > MAXIMUM_RECORDS) {
                throw MetadataParsingException.limit("mobi-record-count", MAXIMUM_RECORDS, "records");
            }
            var offsets = recordOffsets(reader, fileSize, recordCount);
            var recordEnd = recordCount > 1 ? offsets[1] : fileSize;
            var recordLength = recordEnd - offsets[0];
            if (recordLength < REQUIRED_MOBI_PREFIX_BYTES || recordLength > MAXIMUM_RECORD_ZERO_BYTES) {
                throw MetadataParsingException.limit("mobi-record-zero", MAXIMUM_RECORD_ZERO_BYTES, "bytes");
            }
            return parseRecord(reader, header, offsets[0], Math.toIntExact(recordLength), budget);
        } catch (ArithmeticException exception) {
            throw MetadataParsingException.limit("mobi-offset", Integer.MAX_VALUE, "bytes", exception);
        }
    }

    private static ParsedBookMetadata parseRecord(
            BudgetedFileReader reader,
            ByteBuffer pdbHeader,
            long recordStart,
            int recordLength,
            MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        var prefix = reader.read(recordStart, REQUIRED_MOBI_PREFIX_BYTES);
        var compression = Short.toUnsignedInt(prefix.getShort(0));
        if (compression != 1 && compression != 2) {
            throw new MetadataParsingException(
                    MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT,
                    "The MOBI compression method is not supported.");
        }
        if (Short.toUnsignedInt(prefix.getShort(12)) != 0) {
            throw new MetadataParsingException(
                    MetadataExtractionResult.ErrorCode.ENCRYPTED_ASSET, "Encrypted MOBI files are not supported.");
        }
        if (!"MOBI".equals(ascii(prefix, MOBI_HEADER_START, 4))) {
            throw corrupt("The MOBI header is missing.");
        }
        var mobiLength = unsignedInt(prefix, 20);
        if (mobiLength < 116
                || mobiLength > MAXIMUM_RECORD_ZERO_BYTES
                || MOBI_HEADER_START + mobiLength > recordLength) {
            throw corrupt("The MOBI header length is invalid.");
        }
        var charset = charset(unsignedInt(prefix, 28));
        var metadata = exth(reader, recordStart, recordLength, Math.toIntExact(mobiLength), charset, budget);
        var title = metadata.title();
        if (title == null) {
            var titleOffset = unsignedInt(prefix, MOBI_HEADER_START + FULL_NAME_OFFSET_FIELD);
            var titleLength = unsignedInt(prefix, MOBI_HEADER_START + FULL_NAME_LENGTH_FIELD);
            title = selectedText(reader, recordStart, recordLength, titleOffset, titleLength, charset, budget);
        }
        if (title == null) {
            title = decode(pdbHeader.array(), StandardCharsets.US_ASCII, budget);
        }
        return new ParsedBookMetadata(
                ExtractedBookMetadata.Format.MOBI,
                title,
                metadata.contributors(),
                metadata.language(),
                metadata.identifiers(),
                "jdk-mobi-seek",
                "3");
    }

    private static ExthMetadata exth(
            BudgetedFileReader reader,
            long recordStart,
            int recordLength,
            int mobiLength,
            Charset charset,
            MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        var flags = reader.read(recordStart + MOBI_HEADER_START + EXTH_FLAGS_FIELD, Integer.BYTES);
        if ((Integer.toUnsignedLong(flags.getInt(0)) & 0x40) == 0) {
            return ExthMetadata.empty();
        }
        var start = MOBI_HEADER_START + mobiLength;
        requireAvailable(recordLength, start, 12);
        var header = reader.read(recordStart + start, 12);
        if (!"EXTH".equals(ascii(header, 0, 4))) {
            throw corrupt("The MOBI extended metadata header is invalid.");
        }
        var length = unsignedInt(header, 4);
        var count = unsignedInt(header, 8);
        if (length < 12 || length > MAXIMUM_EXTH_BYTES || length > recordLength - start) {
            throw MetadataParsingException.limit("mobi-exth-structure", MAXIMUM_EXTH_BYTES, "bytes");
        }
        if (count > MAXIMUM_EXTH_RECORDS) {
            throw MetadataParsingException.limit("mobi-exth-record-count", MAXIMUM_EXTH_RECORDS, "records");
        }
        var position = start + 12L;
        var end = start + length;
        var contributors = new ArrayList<String>();
        var identifiers = new LinkedHashMap<String, String>();
        String title = null;
        String language = null;
        for (var index = 0L; index < count; index++) {
            requireAvailable(recordLength, position, 8);
            var recordHeader = reader.read(recordStart + position, 8);
            var type = unsignedInt(recordHeader, 0);
            var itemLength = unsignedInt(recordHeader, 4);
            if (itemLength < 8 || itemLength > end - position) {
                throw corrupt("The MOBI extended metadata contains an invalid record length.");
            }
            if (selectedExthType(type)) {
                var value =
                        selectedText(reader, recordStart, recordLength, position + 8, itemLength - 8, charset, budget);
                if (value != null) {
                    switch ((int) type) {
                        case 100 -> contributors.add(value);
                        case 104 -> identifiers.putIfAbsent("isbn", value);
                        case 113 -> identifiers.putIfAbsent("asin", value);
                        case 503 -> title = value;
                        case 524 -> language = value;
                        default -> throw new IllegalStateException("Selected EXTH type was not handled.");
                    }
                }
            }
            position = Math.addExact(position, itemLength);
            budget.checkpoint();
        }
        return new ExthMetadata(title, java.util.List.copyOf(contributors), language, Map.copyOf(identifiers));
    }

    private static boolean selectedExthType(long type) {
        return type == 100 || type == 104 || type == 113 || type == 503 || type == 524;
    }

    private static long[] recordOffsets(BudgetedFileReader reader, long fileSize, int recordCount)
            throws IOException, MetadataParsingException {
        var directoryBytes = Math.multiplyExact(recordCount, 8);
        var directory = reader.read(PDB_HEADER_BYTES, directoryBytes);
        var minimumOffset = PDB_HEADER_BYTES + (long) directoryBytes;
        var offsets = new long[recordCount];
        long previous = -1;
        for (var index = 0; index < recordCount; index++) {
            var offset = unsignedInt(directory, index * 8);
            if (offset < minimumOffset || offset >= fileSize || offset <= previous) {
                throw corrupt("The MOBI record directory contains an invalid offset.");
            }
            offsets[index] = offset;
            previous = offset;
        }
        return offsets;
    }

    private static String selectedText(
            BudgetedFileReader reader,
            long recordStart,
            int recordLength,
            long offset,
            long length,
            Charset charset,
            MetadataResourceBudget budget)
            throws IOException, MetadataParsingException {
        if (length == 0) {
            return null;
        }
        requireAvailable(recordLength, offset, length);
        if (length > budget.limits().maximumSelectedValueBytes()) {
            throw MetadataParsingException.limit(
                    "selected-value", budget.limits().maximumSelectedValueBytes(), "bytes");
        }
        var bytes = reader.read(recordStart + offset, Math.toIntExact(length)).array();
        return decode(bytes, charset, budget);
    }

    private static String decode(byte[] bytes, Charset charset, MetadataResourceBudget budget)
            throws MetadataParsingException {
        budget.checkSelectedValueBytes(bytes.length);
        var value = new String(bytes, charset);
        var terminator = value.indexOf('\0');
        return SecureXml.normalizedSelected(terminator >= 0 ? value.substring(0, terminator) : value, budget);
    }

    private static void requireSignature(ByteBuffer header) throws MetadataParsingException {
        if (!"BOOKMOBI".equals(ascii(header, 60, 8))) {
            throw corrupt("The file does not contain a MOBI signature.");
        }
    }

    private static Charset charset(long code) throws MetadataParsingException {
        if (code == 65001) {
            return StandardCharsets.UTF_8;
        }
        if (code == 1252) {
            return Charset.forName("windows-1252");
        }
        throw new MetadataParsingException(
                MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT, "The MOBI text encoding is not supported.");
    }

    private static String ascii(ByteBuffer buffer, int offset, int length) throws MetadataParsingException {
        requireAvailable(buffer.limit(), offset, length);
        return new String(buffer.array(), offset, length, StandardCharsets.US_ASCII);
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) throws MetadataParsingException {
        requireAvailable(buffer.limit(), offset, Integer.BYTES);
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static void requireAvailable(long available, long offset, long length) throws MetadataParsingException {
        if (offset < 0 || length < 0 || offset > available - length) {
            throw corrupt("The MOBI metadata contains an invalid offset.");
        }
    }

    private static MetadataParsingException corrupt(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message);
    }

    private record ExthMetadata(
            String title, java.util.List<String> contributors, String language, Map<String, String> identifiers) {

        private static ExthMetadata empty() {
            return new ExthMetadata(null, java.util.List.of(), null, Map.of());
        }
    }
}
