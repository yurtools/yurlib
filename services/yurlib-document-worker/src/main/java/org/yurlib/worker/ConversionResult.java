package org.yurlib.worker;

record ConversionResult(
        State state,
        String outputSha256,
        Long outputByteSize,
        String converterName,
        String converterVersion,
        String errorCode,
        String safeDiagnostic) {

    static ConversionResult succeeded(String sha256, long byteSize) {
        return new ConversionResult(State.SUCCEEDED, sha256, byteSize, "calibre", "9.15.0", null, null);
    }

    static ConversionResult failed(String code, String diagnostic) {
        return new ConversionResult(State.FAILED_SAFE, null, null, "calibre", "9.15.0", code, diagnostic);
    }

    static ConversionResult cancelled() {
        return new ConversionResult(State.CANCELLED, null, null, "calibre", "9.15.0", null, null);
    }

    enum State {
        SUCCEEDED,
        FAILED_SAFE,
        CANCELLED
    }
}
