package org.yurlib.server.security;

import java.security.Principal;
import java.util.List;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
class OwnerSessionController {

    private final OwnerAccessMode accessMode;

    OwnerSessionController(OwnerAccessMode accessMode) {
        this.accessMode = accessMode;
    }

    @GetMapping
    OwnerSessionResponse session(
            Principal principal, @RequestAttribute(name = "_csrf", required = false) CsrfToken csrfToken) {
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        var developmentMode = accessMode == OwnerAccessMode.LOOPBACK_DEVELOPMENT;
        var user = principal instanceof org.springframework.security.core.Authentication authentication
                        && authentication.getPrincipal() instanceof PersistedUserPrincipal persisted
                ? persisted
                : null;
        return new OwnerSessionResponse(
                accessMode,
                developmentMode || principal != null,
                principal == null ? null : principal.getName(),
                user != null && user.owner(),
                user == null
                        ? List.of()
                        : user.capabilities().stream().map(Enum::name).sorted().toList());
    }

    record OwnerSessionResponse(
            OwnerAccessMode mode, boolean authenticated, String username, boolean owner, List<String> capabilities) {}
}
