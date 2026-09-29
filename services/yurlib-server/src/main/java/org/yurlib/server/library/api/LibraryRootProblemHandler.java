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
import org.yurlib.server.library.application.AssetContentFailure;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.application.ScanJobFailure;

@RestControllerAdvice(
        assignableTypes = {
            LibraryRootController.class,
            ScanJobController.class,
            CatalogController.class,
            AssetContentController.class
        })
public class LibraryRootProblemHandler {

    @ExceptionHandler(LibraryRootFailure.class)
    ProblemDetail handleLibraryRootFailure(LibraryRootFailure failure, HttpServletRequest request) {
        var status =
                switch (failure.code()) {
                    case ROOT_NOT_ALLOWED, PATH_ESCAPE -> HttpStatus.BAD_REQUEST;
                    case ROOT_UNAVAILABLE, ROOT_IDENTITY_MISMATCH, ROOT_ALREADY_CONFIGURED -> HttpStatus.CONFLICT;
                };
        return problem(status, failure.code().name(), failure.getMessage(), request);
    }

    @ExceptionHandler(ScanJobFailure.class)
    ProblemDetail handleScanJobFailure(ScanJobFailure failure, HttpServletRequest request) {
        var status =
                switch (failure.code()) {
                    case ROOT_NOT_FOUND, JOB_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case SCAN_ALREADY_ACTIVE -> HttpStatus.CONFLICT;
                };
        return problem(status, failure.code().name(), failure.getMessage(), request);
    }

    @ExceptionHandler(AssetContentFailure.class)
    ProblemDetail handleAssetContentFailure(AssetContentFailure failure, HttpServletRequest request) {
        var status =
                failure.code() == AssetContentFailure.Code.ASSET_NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
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
        problem.setTitle(title(code));
        problem.setType(
                URI.create("urn:yurlib:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        return problem;
    }

    private static String title(String code) {
        return switch (code) {
            case "ROOT_NOT_ALLOWED" -> "Library root not allowed";
            case "ROOT_UNAVAILABLE" -> "Library root unavailable";
            case "ROOT_IDENTITY_MISMATCH" -> "Library root identity mismatch";
            case "ROOT_ALREADY_CONFIGURED" -> "Library root already configured";
            case "PATH_ESCAPE" -> "Library path rejected";
            case "ROOT_NOT_FOUND" -> "Library root not found";
            case "JOB_NOT_FOUND" -> "Scan job not found";
            case "SCAN_ALREADY_ACTIVE" -> "Scan already active";
            case "ASSET_NOT_FOUND" -> "Original asset not found";
            case "ASSET_UNAVAILABLE" -> "Original asset unavailable";
            case "FILE_UNSTABLE" -> "Original asset changed";
            default -> "Invalid request";
        };
    }
}
