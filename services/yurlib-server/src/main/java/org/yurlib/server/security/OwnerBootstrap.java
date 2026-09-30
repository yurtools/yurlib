package org.yurlib.server.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

final class OwnerBootstrap implements ApplicationRunner {

    private final JdbcUserAccountStore users;
    private final OwnerAccessProperties properties;
    private final PasswordEncoder passwordEncoder;

    OwnerBootstrap(JdbcUserAccountStore users, OwnerAccessProperties properties, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (properties.recoveryMode()) {
            var owner = users.findOwner()
                    .orElseThrow(() -> new IllegalStateException("Owner recovery requires a persisted owner."));
            var recoveryPassword = OwnerSecurityConfiguration.requireCredential(
                    properties.recoveryPassword(), "YURLIB_OWNER_RECOVERY_PASSWORD");
            users.resetPassword(owner.userId(), owner.userId(), passwordEncoder.encode(recoveryPassword), true);
            return;
        }
        if (users.hasOwner()) {
            return;
        }
        var username = OwnerSecurityConfiguration.requireCredential(properties.username(), "YURLIB_OWNER_USERNAME");
        var password = OwnerSecurityConfiguration.requireCredential(properties.password(), "YURLIB_OWNER_PASSWORD");
        users.bootstrapOwner(username, passwordEncoder.encode(password));
    }
}
