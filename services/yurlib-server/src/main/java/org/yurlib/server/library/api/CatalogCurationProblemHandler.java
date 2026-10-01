package org.yurlib.server.library.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.yurlib.server.library.application.CatalogCurationFailure;

@RestControllerAdvice(assignableTypes = CatalogCurationController.class)
final class CatalogCurationProblemHandler {

    @ExceptionHandler(CatalogCurationFailure.class)
    ProblemDetail handle(CatalogCurationFailure failure, HttpServletRequest request) {
        var status =
                switch (failure.code()) {
                    case WORK_NOT_FOUND, CONTRIBUTOR_NOT_FOUND, REVIEW_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case VERSION_CONFLICT -> HttpStatus.CONFLICT;
                };
        var problem = ProblemDetail.forStatusAndDetail(status, failure.getMessage());
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", failure.code().name());
        problem.setProperty("correlationId", request.getAttribute("correlationId"));
        return problem;
    }
}
