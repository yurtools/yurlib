package org.yurlib.server.library.application;

import java.io.Serial;

public final class PersonalLibraryFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Code code;

    public PersonalLibraryFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public PersonalLibraryFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        PERSONAL_ITEM_NOT_FOUND,
        PERSONAL_VERSION_CONFLICT,
        COLLECTION_NAME_EXISTS,
        EDITION_NOT_IN_WORK
    }
}
