package org.yurlib.server.library.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.yurlib.server.library.application.AssetContentFailure;
import org.yurlib.server.library.application.ConversionFailure;

@RestControllerAdvice(assignableTypes = ConversionController.class)
public class ConversionProblemHandler {

    @ExceptionHandler(ConversionFailure.class)
    ProblemDetail handle(ConversionFailure failure, HttpServletRequest request) {
        var status =
                switch (failure.code()) {
                    case ASSET_NOT_FOUND, JOB_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case ROUTE_NOT_SUPPORTED, CONVERSION_LIMIT_EXCEEDED -> HttpStatus.valueOf(422);
                    case MANAGED_ROOT_UNAVAILABLE -> HttpStatus.CONFLICT;
                    case VERSION_CONFLICT -> HttpStatus.CONFLICT;
                    case CONVERSION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        var problem = ProblemDetail.forStatusAndDetail(status, failure.getMessage());
        problem.setTitle("Conversion request failed");
        problem.setType(URI.create("urn:yurlib:problem:"
                + failure.code().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", failure.code().name());
        problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(AssetContentFailure.class)
    ProblemDetail handleAssetContent(AssetContentFailure failure, HttpServletRequest request) {
        var status =
                failure.code() == AssetContentFailure.Code.ASSET_NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        var problem = ProblemDetail.forStatusAndDetail(status, failure.getMessage());
        problem.setTitle("Conversion source unavailable");
        problem.setType(URI.create("urn:yurlib:problem:"
                + failure.code().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", failure.code().name());
        problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
