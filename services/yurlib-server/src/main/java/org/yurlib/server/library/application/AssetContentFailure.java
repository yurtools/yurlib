package org.yurlib.server.library.application;

import java.io.Serial;

public final class AssetContentFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Code code;

    public AssetContentFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public AssetContentFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        ASSET_NOT_FOUND,
        ASSET_UNAVAILABLE,
        FILE_UNSTABLE,
        PATH_ESCAPE
    }
}
