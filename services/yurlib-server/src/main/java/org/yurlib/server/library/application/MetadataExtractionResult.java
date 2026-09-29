package org.yurlib.server.library.application;

public record MetadataExtractionResult(
        State state, ExtractedBookMetadata metadata, ErrorCode errorCode, String safeDiagnostic) {

    public MetadataExtractionResult {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (state == State.EXTRACTED && (metadata == null || errorCode != null || safeDiagnostic != null)) {
            throw new IllegalArgumentException("an extracted result must contain metadata only");
        }
        if (state != State.EXTRACTED && (metadata != null || errorCode == null || safeDiagnostic == null)) {
            throw new IllegalArgumentException("a non-extracted result must contain a safe error only");
        }
    }

    public static MetadataExtractionResult extracted(ExtractedBookMetadata metadata) {
        return new MetadataExtractionResult(State.EXTRACTED, metadata, null, null);
    }

    public static MetadataExtractionResult deferred(ErrorCode code, String safeDiagnostic) {
        return new MetadataExtractionResult(State.DEFERRED, null, code, safeDiagnostic);
    }

    public static MetadataExtractionResult failed(ErrorCode code, String safeDiagnostic) {
        return new MetadataExtractionResult(State.FAILED, null, code, safeDiagnostic);
    }

    public enum State {
        EXTRACTED,
        DEFERRED,
        FAILED
    }

    public enum ErrorCode {
        FILE_UNSTABLE,
        UNSUPPORTED_FORMAT,
        ENCRYPTED_ASSET,
        CORRUPT_ASSET,
        PARSE_LIMIT_EXCEEDED
    }
}
