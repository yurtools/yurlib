package org.yurlib.server.library.application;

public record ConversionWorkerResult(
        State state,
        String outputSha256,
        Long outputByteSize,
        String converterName,
        String converterVersion,
        String errorCode,
        String safeDiagnostic) {

    public enum State {
        SUCCEEDED,
        FAILED_SAFE,
        CANCELLED
    }
}
