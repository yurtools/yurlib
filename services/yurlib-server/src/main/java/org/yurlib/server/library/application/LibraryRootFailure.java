package org.yurlib.server.library.application;

public final class LibraryRootFailure extends RuntimeException {

    private final Code code;

    public LibraryRootFailure(Code code, String safeMessage) {
        super(safeMessage);
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
        PATH_ESCAPE
    }
}
