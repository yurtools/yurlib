package org.yurlib.server.library.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.yurlib.server.library.application.PersonalLibraryFailure;

@RestControllerAdvice(assignableTypes = PersonalLibraryController.class)
final class PersonalLibraryProblemHandler {

    @ExceptionHandler(PersonalLibraryFailure.class)
    ProblemDetail handle(PersonalLibraryFailure failure, HttpServletRequest request) {
        var status =
                switch (failure.code()) {
                    case PERSONAL_ITEM_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case PERSONAL_VERSION_CONFLICT, COLLECTION_NAME_EXISTS -> HttpStatus.CONFLICT;
                    case EDITION_NOT_IN_WORK -> HttpStatus.BAD_REQUEST;
                };
        return problem(status, failure.code().name(), failure.getMessage(), request);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    ProblemDetail handleInvalidRequest(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.", request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(
                URI.create("urn:yurlib:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        return problem;
    }
}
