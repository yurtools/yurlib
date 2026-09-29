package org.yurlib.server.library.application;

import java.io.Serial;

public final class ScanJobFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Code code;

    public ScanJobFailure(Code code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public ScanJobFailure(Code code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        ROOT_NOT_FOUND,
        JOB_NOT_FOUND,
        SCAN_ALREADY_ACTIVE
    }
}
