package org.yurlib.server.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!loopback-dev")
@RequestMapping("/api/v1/admin/users")
class UserAdministrationController {

    private final JdbcUserAccountStore users;
    private final PasswordEncoder passwordEncoder;

    UserAdministrationController(JdbcUserAccountStore users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    List<UserAccountSummary> list() {
        return users.listUsers();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserAccountSummary create(
            @AuthenticationPrincipal PersistedUserPrincipal actor, @Valid @RequestBody CreateUserRequest request) {
        return users.createUser(actor.userId(), request.username(), passwordEncoder.encode(request.password()));
    }

    @PutMapping("/{userId}/enabled")
    UserAccountSummary setEnabled(
            @AuthenticationPrincipal PersistedUserPrincipal actor,
            @PathVariable UUID userId,
            @Valid @RequestBody EnabledRequest request) {
        return users.setEnabled(actor.userId(), userId, request.enabled());
    }

    @PutMapping("/{userId}/credential")
    UserAccountSummary resetCredential(
            @AuthenticationPrincipal PersistedUserPrincipal actor,
            @PathVariable UUID userId,
            @Valid @RequestBody CredentialRequest request) {
        return users.resetPassword(actor.userId(), userId, passwordEncoder.encode(request.password()), false);
    }

    @PutMapping("/{userId}/capabilities")
    UserAccountSummary replaceCapabilities(
            @AuthenticationPrincipal PersistedUserPrincipal actor,
            @PathVariable UUID userId,
            @Valid @RequestBody CapabilitiesRequest request) {
        return users.replaceCapabilities(actor.userId(), userId, request.capabilities());
    }

    @PutMapping("/{userId}/root-denies/{rootId}")
    UserAccountSummary denyRoot(
            @AuthenticationPrincipal PersistedUserPrincipal actor,
            @PathVariable UUID userId,
            @PathVariable UUID rootId) {
        return users.denyRoot(actor.userId(), userId, rootId);
    }

    @DeleteMapping("/{userId}/root-denies/{rootId}")
    UserAccountSummary allowRoot(
            @AuthenticationPrincipal PersistedUserPrincipal actor,
            @PathVariable UUID userId,
            @PathVariable UUID rootId) {
        return users.allowRoot(actor.userId(), userId, rootId);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeUser(@AuthenticationPrincipal PersistedUserPrincipal actor, @PathVariable UUID userId) {
        users.removeUser(actor.userId(), userId);
    }

    record CreateUserRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 12, max = 200) String password) {}

    record EnabledRequest(boolean enabled) {}

    record CredentialRequest(
            @NotBlank @Size(min = 12, max = 200) String password) {}

    record CapabilitiesRequest(@NotNull Set<Capability> capabilities) {
        CapabilitiesRequest {
            capabilities = capabilities == null ? null : Set.copyOf(capabilities);
        }
    }
}
