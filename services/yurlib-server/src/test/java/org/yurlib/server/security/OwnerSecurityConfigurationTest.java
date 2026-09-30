package org.yurlib.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yurlib.server.api.SystemController;
import org.yurlib.server.library.api.CorrelationIdFilter;

@WebMvcTest(SystemController.class)
@ImportAutoConfiguration({
    SecurityAutoConfiguration.class,
    ServletWebSecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class
})
@Import({
    OwnerSecurityConfiguration.class,
    OwnerSessionController.class,
    SecurityProblemWriter.class,
    CorrelationIdFilter.class
})
@ActiveProfiles("owner-test")
@TestPropertySource(
        properties = {
            "yurlib.security.owner.username=owner",
            "yurlib.security.owner.password=correct horse battery staple"
        })
class OwnerSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publishesSessionStateAndCsrfCookieWithoutAuthenticating() throws Exception {
        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(jsonPath("$.mode").value("OWNER"))
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void rejectsUnauthenticatedApiRequestsWithCorrelatedProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/system"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void requiresCsrfForLoginAndUsesTheAuthenticatedSession() throws Exception {
        mockMvc.perform(post("/api/v1/session")
                        .param("username", "owner")
                        .param("password", "correct horse battery staple"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        var login = mockMvc.perform(withCsrf(post("/api/v1/session"))
                        .param("username", "owner")
                        .param("password", "correct horse battery staple"))
                .andExpect(status().isNoContent())
                .andReturn();
        HttpSession session = login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/v1/system").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Yurlib"));
        mockMvc.perform(get("/api/v1/session").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("owner"));
    }

    @Test
    void rejectsInvalidCredentialsWithoutDisclosingTheUsername() throws Exception {
        mockMvc.perform(withCsrf(post("/api/v1/session"))
                        .param("username", "owner")
                        .param("password", "incorrect"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Owner authentication is required."));
    }

    @Test
    void protectsRootScanAndSensitiveActuatorRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/library-roots")).andExpect(status().isUnauthorized());
        mockMvc.perform(withCsrf(post("/api/v1/library-roots/root-1/scans"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingOwnerCredentials() {
        assertThatThrownBy(() -> OwnerSecurityConfiguration.requireCredential(" ", "SECRET"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECRET");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        var tokenResponse = mockMvc.perform(get("/api/v1/session")).andReturn().getResponse();
        Cookie cookie = tokenResponse.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return request.cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue());
    }
}
