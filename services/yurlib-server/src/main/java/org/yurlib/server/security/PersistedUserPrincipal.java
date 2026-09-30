package org.yurlib.server.security;

import java.io.Serial;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record PersistedUserPrincipal(
        UUID userId,
        String username,
        String password,
        boolean owner,
        boolean enabled,
        long authorizationVersion,
        Set<Capability> capabilities)
        implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    public PersistedUserPrincipal {
        capabilities = Set.copyOf(capabilities);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        var authorities = new java.util.LinkedHashSet<GrantedAuthority>();
        if (owner) {
            authorities.add(new SimpleGrantedAuthority("ROLE_OWNER"));
        }
        capabilities.stream().map(Enum::name).map(SimpleGrantedAuthority::new).forEach(authorities::add);
        return Set.copyOf(authorities);
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
