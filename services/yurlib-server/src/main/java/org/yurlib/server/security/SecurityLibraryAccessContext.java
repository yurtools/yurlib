package org.yurlib.server.security;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.application.LibraryAccessContext;

@Component
final class SecurityLibraryAccessContext implements LibraryAccessContext {

    private static final UUID UNRESTRICTED_PLACEHOLDER = new UUID(0, 0);
    private final OwnerAccessMode accessMode;

    SecurityLibraryAccessContext(OwnerAccessMode accessMode) {
        this.accessMode = accessMode;
    }

    @Override
    public Access current() {
        if (accessMode == OwnerAccessMode.LOOPBACK_DEVELOPMENT) {
            return new Access(UNRESTRICTED_PLACEHOLDER, true);
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PersistedUserPrincipal user) {
            return new Access(user.userId(), false);
        }
        throw new IllegalStateException("Authenticated library access is required.");
    }
}
