package org.yurlib.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class StaleAuthorizationFilter extends OncePerRequestFilter {

    private final JdbcUserAccountStore users;
    private final SecurityProblemWriter problemWriter;

    StaleAuthorizationFilter(JdbcUserAccountStore users, SecurityProblemWriter problemWriter) {
        this.users = users;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PersistedUserPrincipal sessionUser) {
            var current = users.findById(sessionUser.userId());
            if (current.isEmpty()
                    || !current.get().enabled()
                    || current.get().authorizationVersion() != sessionUser.authorizationVersion()) {
                var session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                problemWriter.authenticationRequired(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
