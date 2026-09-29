package org.yurlib.server.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("loopback-dev")
class LoopbackDevelopmentSecurityConfiguration {

    @Bean
    OwnerAccessMode ownerAccessMode() {
        return OwnerAccessMode.LOOPBACK_DEVELOPMENT;
    }

    @Bean
    UserDetailsService loopbackUserDetails() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            justification = "HttpSecurity.build declares Exception in the Spring Security API.")
    SecurityFilterChain loopbackDevelopmentSecurityFilterChain(
            HttpSecurity http, @Value("${server.address:127.0.0.1}") String serverAddress) throws Exception {
        requireLoopback(serverAddress);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    static void requireLoopback(String serverAddress) {
        try {
            if (serverAddress.isBlank() || !InetAddress.getByName(serverAddress).isLoopbackAddress()) {
                throw new IllegalStateException("The loopback-dev profile requires server.address to be loopback.");
            }
        } catch (UnknownHostException failure) {
            throw new IllegalStateException("The loopback-dev profile has an invalid server.address.", failure);
        }
    }
}
