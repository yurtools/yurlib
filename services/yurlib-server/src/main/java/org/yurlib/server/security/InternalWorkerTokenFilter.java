package org.yurlib.server.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.yurlib.server.library.infrastructure.config.PdfWorkerProperties;

final class InternalWorkerTokenFilter extends org.springframework.web.filter.OncePerRequestFilter {

    private final byte[] expected;

    InternalWorkerTokenFilter(PdfWorkerProperties properties) {
        this.expected = properties.token().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @SuppressFBWarnings(
            value = "SERVLET_HEADER",
            justification =
                    "The bearer header is intentionally treated as untrusted input and compared in constant time.")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (expected.length == 0) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        var provided = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!MessageDigest.isEqual(expected, provided)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
