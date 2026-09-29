package org.yurlib.server.library.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";
    public static final String HEADER = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var supplied = request.getHeader(HEADER);
        var correlationId = canonicalCorrelationId(supplied);
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        filterChain.doFilter(request, response);
    }

    private static String canonicalCorrelationId(String supplied) {
        if (supplied != null) {
            try {
                return UUID.fromString(supplied).toString();
            } catch (IllegalArgumentException ignored) {
                // A fresh identifier prevents untrusted values from reaching response headers and logs.
            }
        }
        return UUID.randomUUID().toString();
    }
}
