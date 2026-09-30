package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.yurlib.server.library.application.MetadataExtractionResult;

final class ZipSecurityInspector {

    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int MAXIMUM_TRAILER_BYTES = 65_557;

    private ZipSecurityInspector() {}

    static void inspect(Path path, MetadataResourceBudget budget) throws IOException, MetadataParsingException {
        try (var reader = BudgetedFileReader.open(path, budget)) {
            var trailerSize = (int) Math.min(reader.size(), MAXIMUM_TRAILER_BYTES);
            var trailer = reader.read(reader.size() - trailerSize, trailerSize).order(ByteOrder.LITTLE_ENDIAN);
            var end = findEndRecord(trailer.array());
            if (end < 0) {
                throw corrupt("The EPUB central directory is missing.");
            }
            var entries = unsignedShort(trailer, end + 10);
            var centralSize = unsignedInt(trailer, end + 12);
            var centralOffset = unsignedInt(trailer, end + 16);
            if (entries > budget.limits().maximumArchiveEntries()) {
                throw MetadataParsingException.limit(
                        "archive-entry-count", budget.limits().maximumArchiveEntries(), "entries");
            }
            if (centralSize > budget.limits().maximumArchiveDirectoryBytes()
                    || centralSize > reader.size()
                    || centralOffset > reader.size() - centralSize) {
                throw MetadataParsingException.limit(
                        "archive-directory", budget.limits().maximumArchiveDirectoryBytes(), "bytes");
            }
            var directory =
                    reader.read(centralOffset, Math.toIntExact(centralSize)).order(ByteOrder.LITTLE_ENDIAN);
            for (var index = 0; index < entries; index++) {
                if (directory.remaining() < 46 || directory.getInt() != CENTRAL_SIGNATURE) {
                    throw corrupt("The EPUB central directory is invalid.");
                }
                directory.position(directory.position() + 4);
                var flags = Short.toUnsignedInt(directory.getShort());
                if ((flags & 1) != 0) {
                    throw new MetadataParsingException(
                            MetadataExtractionResult.ErrorCode.ENCRYPTED_ASSET,
                            "Encrypted EPUB entries are not supported.");
                }
                directory.position(directory.position() + 18);
                var nameLength = Short.toUnsignedInt(directory.getShort());
                var extraLength = Short.toUnsignedInt(directory.getShort());
                var commentLength = Short.toUnsignedInt(directory.getShort());
                directory.position(directory.position() + 12);
                var variableLength = nameLength + extraLength + commentLength;
                if (variableLength > directory.remaining()) {
                    throw corrupt("The EPUB central directory is invalid.");
                }
                directory.position(directory.position() + variableLength);
            }
        } catch (ArithmeticException exception) {
            throw MetadataParsingException.limit(
                    "archive-directory", budget.limits().maximumArchiveDirectoryBytes(), "bytes", exception);
        }
    }

    private static int findEndRecord(byte[] bytes) {
        for (var index = bytes.length - 22; index >= 0; index--) {
            if ((bytes[index] & 0xff) == 0x50
                    && (bytes[index + 1] & 0xff) == 0x4b
                    && (bytes[index + 2] & 0xff) == 0x05
                    && (bytes[index + 3] & 0xff) == 0x06) {
                return index;
            }
        }
        return -1;
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static MetadataParsingException corrupt(String message) {
        return new MetadataParsingException(MetadataExtractionResult.ErrorCode.CORRUPT_ASSET, message);
    }
}
