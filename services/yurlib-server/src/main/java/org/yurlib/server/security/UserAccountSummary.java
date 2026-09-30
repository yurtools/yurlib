package org.yurlib.server.security;

import java.util.Set;
import java.util.UUID;

public record UserAccountSummary(
        UUID id,
        String username,
        boolean owner,
        boolean enabled,
        long authorizationVersion,
        Set<Capability> capabilities,
        Set<UUID> deniedRootIds) {

    public UserAccountSummary {
        capabilities = Set.copyOf(capabilities);
        deniedRootIds = Set.copyOf(deniedRootIds);
    }
}
