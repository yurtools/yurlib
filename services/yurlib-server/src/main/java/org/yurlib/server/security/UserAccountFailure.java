package org.yurlib.server.security;

public final class UserAccountFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final Code code;

    UserAccountFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    UserAccountFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        USER_NOT_FOUND,
        USERNAME_EXISTS,
        OWNER_MUTATION_FORBIDDEN,
        ROOT_NOT_FOUND
    }
}
