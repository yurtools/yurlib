package org.yurlib.server.library.infrastructure.metadata;

import java.io.IOException;
import java.io.Serial;
import org.yurlib.server.library.application.MetadataExtractionResult;

final class MetadataParsingException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final MetadataExtractionResult.ErrorCode code;

    MetadataParsingException(MetadataExtractionResult.ErrorCode code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    MetadataParsingException(MetadataExtractionResult.ErrorCode code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    MetadataExtractionResult.ErrorCode code() {
        return code;
    }

    static MetadataParsingException limit(String resource, long bound, String unit) {
        return new MetadataParsingException(
                MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED,
                "Metadata extraction exceeded the " + resource + " limit (" + bound + " " + unit + ").");
    }

    static MetadataParsingException limit(String resource, long bound, String unit, Throwable cause) {
        return new MetadataParsingException(
                MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED,
                "Metadata extraction exceeded the " + resource + " limit (" + bound + " " + unit + ").",
                cause);
    }

    static MetadataParsingException causedBy(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MetadataParsingException metadataFailure) {
                return metadataFailure;
            }
        }
        return null;
    }
}
