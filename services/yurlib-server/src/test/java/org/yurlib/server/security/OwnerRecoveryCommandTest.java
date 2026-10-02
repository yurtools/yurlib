package org.yurlib.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OwnerRecoveryCommandTest {

    @Test
    void forcesRecoveryModeAfterCallerArguments() {
        assertThat(OwnerRecoveryCommand.recoveryArguments(
                        new String[] {"--yurlib.security.owner.recovery-mode=false", "--example=value"}))
                .containsExactly(
                        "--yurlib.security.owner.recovery-mode=false",
                        "--example=value",
                        "--yurlib.security.owner.recovery-mode=true",
                        "--yurlib.library.scan.worker-enabled=false");
    }
}
