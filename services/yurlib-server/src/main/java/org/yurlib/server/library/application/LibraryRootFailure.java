package org.yurlib.server.library.application;

public final class LibraryRootFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Code code;

    public LibraryRootFailure(Code code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public LibraryRootFailure(Code code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        ROOT_NOT_ALLOWED,
        ROOT_UNAVAILABLE,
        ROOT_IDENTITY_MISMATCH,
        ROOT_ALREADY_CONFIGURED,
        ROOT_NOT_FOUND,
        ROOT_OVERLAP,
        ROOT_IN_USE,
        ROOT_MODE_INVALID,
        PATH_ESCAPE
    }
}
