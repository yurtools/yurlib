package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;

final class MobiMetadataParser implements MetadataParser {

    private static final long MAXIMUM_SOURCE_BYTES = 256L * 1024 * 1024;
    private static final int PDB_HEADER_BYTES = 78;
    private static final int MAXIMUM_RECORDS = 4096;
    private static final int MAXIMUM_RECORD_ZERO_BYTES = 1024 * 1024;
    private static final int MAXIMUM_EXTH_RECORDS = 1024;
    private static final int MAXIMUM_TEXT_BYTES = 64 * 1024;
    private static final int MOBI_HEADER_START = 16;
    private static final int FULL_NAME_OFFSET_FIELD = 0x44;
    private static final int FULL_NAME_LENGTH_FIELD = 0x48;
    private static final int EXTH_FLAGS_FIELD = 0x70;

    @Override
    public ExtractedBookMetadata.Format format() {
        return ExtractedBookMetadata.Format.MOBI;
    }

    @Override
    public long maximumSourceBytes() {
        return MAXIMUM_SOURCE_BYTES;
    }

    @Override
    public ParsedBookMetadata parse(Path file) throws IOException, MetadataParsingException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var fileSize = channel.size();
            var header = read(channel, 0, PDB_HEADER_BYTES);
            requireSignature(header);
            var recordCount = Short.toUnsignedInt(header.getShort(76));
            if (recordCount == 0 || recordCount > MAXIMUM_RECORDS) {
                throw limit("The MOBI record count exceeds the configured limit.");
            }
            var offsets = recordOffsets(channel, fileSize, recordCount);
            var recordEnd = recordCount > 1 ? offsets[1] : fileSize;
            var recordLength = recordEnd - offsets[0];
            if (recordLength < 32 || recordLength > MAXIMUM_RECORD_ZERO_BYTES) {
                throw limit("The MOBI metadata record exceeds the configured limit.");
            }
            var record = read(channel, offsets[0], Math.toIntExact(recordLength));
            return parseRecord(header, record);
        } catch (ArithmeticException exception) {
            throw limit("The MOBI file contains an invalid offset.", exception);
        }
    }

    private static ParsedBookMetadata parseRecord(ByteBuffer pdbHeader, ByteBuffer record)
            throws MetadataParsingException {
        var compression = Short.toUnsignedInt(record.getShort(0));
        if (compression != 1 && compression != 2) {
            throw new MetadataParsingException(
                    MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT,
                    "The MOBI compression method is not supported.");
        }
        if (Short.toUnsignedInt(record.getShort(12)) != 0) {
            throw new MetadataParsingException(
                    MetadataExtractionResult.ErrorCode.ENCRYPTED_ASSET, "Encrypted MOBI files are not supported.");
        }
        requireAvailable(record, MOBI_HEADER_START, 24);
        if (!"MOBI".equals(ascii(record, MOBI_HEADER_START, 4))) {
            throw corrupt("The MOBI header is missing.");
        }
        var mobiLength = unsignedInt(record, 20);
        if (mobiLength < 116
                || mobiLength > MAXIMUM_RECORD_ZERO_BYTES
                || MOBI_HEADER_START + mobiLength > record.limit()) {
            throw corrupt("The MOBI header length is invalid.");
        }
        var charset = charset(unsignedInt(record, 28));
        var metadata = exth(record, Math.toIntExact(mobiLength), charset);
        var title = metadata.title;
        if (title == null) {
            var titleOffset = unsignedInt(record, MOBI_HEADER_START + FULL_NAME_OFFSET_FIELD);
            var titleLength = unsignedInt(record, MOBI_HEADER_START + FULL_NAME_LENGTH_FIELD);
            title = boundedText(record, titleOffset, titleLength, charset);
        }
        if (title == null) {
            title = decode(pdbHeader.array(), 0, 32, StandardCharsets.US_ASCII);
        }
        return new ParsedBookMetadata(
                ExtractedBookMetadata.Format.MOBI,
                title,
                metadata.contributors,
                metadata.language,
                metadata.identifiers,
                "jdk-mobi",
                "2");
    }

    private static ExthMetadata exth(ByteBuffer record, int mobiLength, Charset charset)
            throws MetadataParsingException {
        if (mobiLength < 116 || (unsignedInt(record, MOBI_HEADER_START + EXTH_FLAGS_FIELD) & 0x40) == 0) {
            return ExthMetadata.empty();
        }
        var start = MOBI_HEADER_START + mobiLength;
        requireAvailable(record, start, 12);
        if (!"EXTH".equals(ascii(record, start, 4))) {
            throw corrupt("The MOBI extended metadata header is invalid.");
        }
        var length = unsignedInt(record, start + 4);
        var count = unsignedInt(record, start + 8);
        if (length < 12 || length > record.limit() - start || count > MAXIMUM_EXTH_RECORDS) {
            throw limit("The MOBI extended metadata exceeds the configured limit.");
        }
        var position = start + 12;
        var contributors = new ArrayList<String>();
        var identifiers = new LinkedHashMap<String, String>();
        String title = null;
        String language = null;
        for (var index = 0L; index < count; index++) {
            requireAvailable(record, position, 8);
            var type = unsignedInt(record, position);
            var recordLength = unsignedInt(record, position + 4);
            if (recordLength < 8 || recordLength > MAXIMUM_TEXT_BYTES + 8 || recordLength > start + length - position) {
                throw limit("A MOBI extended metadata record exceeds the configured limit.");
            }
            var value = boundedText(record, position + 8L, recordLength - 8, charset);
            if (value != null) {
                switch ((int) type) {
                    case 100 -> contributors.add(value);
                    case 104 -> identifiers.putIfAbsent("isbn", value);
                    case 113 -> identifiers.putIfAbsent("asin", value);
                    case 503 -> title = value;
                    case 524 -> language = value;
                    default -> {
                        // Metadata outside the approved M1 field set is intentionally ignored.
                    }
                }
            }
            position = Math.toIntExact(position + recordLength);
        }
        return new ExthMetadata(title, java.util.List.copyOf(contributors), language, Map.copyOf(identifiers));
    }

    private static long[] recordOffsets(FileChannel channel, long fileSize, int recordCount)
            throws IOException, MetadataParsingException {
        var directoryBytes = Math.multiplyExact(recordCount, 8);
        var directory = read(channel, PDB_HEADER_BYTES, directoryBytes);
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

    private static void requireSignature(ByteBuffer header) throws MetadataParsingException {
        if (!"BOOKMOBI".equals(ascii(header, 60, 8))) {
            throw corrupt("The file does not contain a MOBI signature.");
        }
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        var buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        while (buffer.hasRemaining()) {
            var read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new IOException("Unexpected end of MOBI file.");
            }
        }
        return buffer.flip();
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

    private static String boundedText(ByteBuffer buffer, long offset, long length, Charset charset)
            throws MetadataParsingException {
        if (length == 0) {
            return null;
        }
        if (length < 0 || length > MAXIMUM_TEXT_BYTES || offset < 0 || offset > buffer.limit() - length) {
            throw limit("A MOBI metadata value exceeds the configured limit.");
        }
        return decode(buffer.array(), Math.toIntExact(offset), Math.toIntExact(length), charset);
    }

    private static String decode(byte[] bytes, int offset, int length, Charset charset) {
        var value = new String(bytes, offset, length, charset);
        var terminator = value.indexOf('\0');
        return SecureXml.normalized(terminator >= 0 ? value.substring(0, terminator) : value);
    }

    private static String ascii(ByteBuffer buffer, int offset, int length) {
        requireAvailableUnchecked(buffer, offset, length);
        return new String(buffer.array(), offset, length, StandardCharsets.US_ASCII);
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) throws MetadataParsingException {
        requireAvailable(buffer, offset, Integer.BYTES);
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static void requireAvailable(ByteBuffer buffer, long offset, long length) throws MetadataParsingException {
        if (offset < 0 || length < 0 || offset > buffer.limit() - length) {
            throw corrupt("The MOBI metadata contains an invalid offset.");
        }
    }

    private static void requireAvailableUnchecked(ByteBuffer buffer, int offset, int length) {
        if (offset < 0 || length < 0 || offset > buffer.limit() - length) {
            throw new IllegalArgumentException("buffer range is invalid");
        }
    }

    private static MetadataParsingException corrupt(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message);
    }

    private static MetadataParsingException limit(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED, message);
    }

    private static MetadataParsingException limit(String message, Throwable cause) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED, message, cause);
    }

    private record ExthMetadata(
            String title, java.util.List<String> contributors, String language, Map<String, String> identifiers) {

        private static ExthMetadata empty() {
            return new ExthMetadata(null, java.util.List.of(), null, Map.of());
        }
    }
}
