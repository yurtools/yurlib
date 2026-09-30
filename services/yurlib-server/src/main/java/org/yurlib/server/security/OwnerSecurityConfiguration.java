package org.yurlib.server.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

@Configuration(proxyBeanMethods = false)
@Profile("!loopback-dev")
@EnableConfigurationProperties(OwnerAccessProperties.class)
class OwnerSecurityConfiguration {

    @Bean
    OwnerAccessMode ownerAccessMode() {
        return OwnerAccessMode.OWNER;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService ownerUserDetails(OwnerAccessProperties properties, PasswordEncoder encoder) {
        var username = requireCredential(properties.username(), "YURLIB_OWNER_USERNAME");
        var password = requireCredential(properties.password(), "YURLIB_OWNER_PASSWORD");
        var owner = User.withUsername(username)
                .password(encoder.encode(password))
                .roles("OWNER")
                .build();
        return new InMemoryUserDetailsManager(owner);
    }

    @Bean
    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            justification = "HttpSecurity.build declares Exception in the Spring Security API.")
    SecurityFilterChain ownerSecurityFilterChain(HttpSecurity http, SecurityProblemWriter problemWriter)
            throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/session",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/error")
                        .permitAll()
                        .requestMatchers("/api/**", "/actuator/**")
                        .hasRole("OWNER")
                        .anyRequest()
                        .permitAll())
                .csrf(CsrfConfigurer::spa)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(login -> login.loginProcessingUrl("/api/v1/session")
                        .successHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((request, response, failure) ->
                                problemWriter.authenticationRequired(request, response)))
                .logout(logout -> logout.logoutUrl("/api/v1/session/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                (request, response, failure) -> problemWriter.authenticationRequired(request, response))
                        .accessDeniedHandler(
                                (request, response, failure) -> problemWriter.accessDenied(request, response)));
        return http.build();
    }

    static String requireCredential(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " must be set outside the repository in owner mode.");
        }
        return value;
    }
}
