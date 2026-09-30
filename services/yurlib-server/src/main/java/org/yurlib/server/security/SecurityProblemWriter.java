package org.yurlib.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.api.CorrelationIdFilter;
import tools.jackson.databind.ObjectMapper;

@Component
final class SecurityProblemWriter {

    private final ObjectMapper objectMapper;

    SecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.rebuild().build();
    }

    void authenticationRequired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(
                request,
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "authentication-required",
                "Authentication required",
                "Owner authentication is required.",
                "AUTHENTICATION_REQUIRED");
    }

    void accessDenied(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(
                request,
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "access-denied",
                "Access denied",
                "The request is not permitted.",
                "ACCESS_DENIED");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String type,
            String title,
            String detail,
            String code)
            throws IOException {
        var correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new SecurityProblem(
                        URI.create("urn:yurlib:problem:" + type), title, status, detail, code, correlationId));
    }

    private record SecurityProblem(
            URI type, String title, int status, String detail, String code, String correlationId) {}
}
