package org.yurlib.server.library.application;

import java.io.Serial;

public final class CatalogCurationFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Code code;

    public CatalogCurationFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        WORK_NOT_FOUND,
        CONTRIBUTOR_NOT_FOUND,
        REVIEW_NOT_FOUND,
        VERSION_CONFLICT
    }
}
