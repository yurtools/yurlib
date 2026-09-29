package org.yurlib.server.library.infrastructure.metadata;

import java.io.Serial;
import org.yurlib.server.library.application.MetadataExtractionResult;

final class MetadataParsingException extends Exception {

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
}
