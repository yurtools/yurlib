package org.yurlib.server.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.yurlib.server.library.api.CorrelationIdFilter;

@RestControllerAdvice(assignableTypes = UserAdministrationController.class)
class UserAccountProblemHandler {

    @ExceptionHandler(UserAccountFailure.class)
    ProblemDetail handle(UserAccountFailure failure, HttpServletRequest request) {
        var status =
                switch (failure.code()) {
                    case USER_NOT_FOUND, ROOT_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case USERNAME_EXISTS, OWNER_MUTATION_FORBIDDEN -> HttpStatus.CONFLICT;
                };
        return problem(status, failure.code().name(), failure.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.", request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        var result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setTitle(code.replace('_', ' '));
        result.setType(
                URI.create("urn:yurlib:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        result.setInstance(URI.create(request.getRequestURI()));
        result.setProperty("code", code);
        result.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        return result;
    }
}
