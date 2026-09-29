package org.yurlib.server.library.api;

import org.yurlib.server.library.domain.FileOutcome;

public record FileFailureResponse(String relativePath, String code, String detail) {

    static FileFailureResponse from(FileOutcome outcome) {
        return new FileFailureResponse(outcome.normalizedRelativePath(), outcome.errorCode(), outcome.safeDiagnostic());
    }
}
