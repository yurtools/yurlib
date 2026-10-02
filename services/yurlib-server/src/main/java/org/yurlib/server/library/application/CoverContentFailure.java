package org.yurlib.server.library.application;

public final class CoverContentFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Code code;

    public CoverContentFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public CoverContentFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        COVER_NOT_FOUND,
        COVER_UNAVAILABLE
    }
}
