package org.yurlib.server.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoopbackDevelopmentSecurityConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "localhost"})
    void acceptsLoopbackAddresses(String address) {
        assertThatCode(() -> LoopbackDevelopmentSecurityConfiguration.requireLoopback(address))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0.0.0.0", "::", "192.0.2.1", "not a host"})
    void rejectsWildcardNonLoopbackAndInvalidAddresses(String address) {
        assertThatThrownBy(() -> LoopbackDevelopmentSecurityConfiguration.requireLoopback(address))
                .isInstanceOf(IllegalStateException.class);
    }
}
