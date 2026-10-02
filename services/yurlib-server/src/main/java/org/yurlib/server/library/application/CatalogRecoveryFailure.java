package org.yurlib.server.library.application;

import java.io.Serial;

public final class CatalogRecoveryFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Code code;

    public CatalogRecoveryFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        SUBJECT_NOT_FOUND,
        INVALID_MERGE,
        VERSION_CONFLICT,
        OPERATION_NOT_FOUND,
        SPLIT_CONFLICT
    }
}
