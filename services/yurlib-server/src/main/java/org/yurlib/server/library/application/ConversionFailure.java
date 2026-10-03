package org.yurlib.server.library.application;

public final class ConversionFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final Code code;

    public ConversionFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ConversionFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        ASSET_NOT_FOUND,
        ROUTE_NOT_SUPPORTED,
        MANAGED_ROOT_UNAVAILABLE,
        JOB_NOT_FOUND,
        VERSION_CONFLICT,
        CONVERSION_LIMIT_EXCEEDED,
        CONVERSION_FAILED
    }
}
