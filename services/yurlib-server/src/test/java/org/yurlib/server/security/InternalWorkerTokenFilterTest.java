package org.yurlib.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yurlib.server.library.infrastructure.config.PdfWorkerProperties;

class InternalWorkerTokenFilterTest {

    @Test
    void acceptsOnlyTheConfiguredBearerToken() throws Exception {
        var filter = filter("correct-token");
        var accepted = new AtomicBoolean();
        var acceptedRequest = new MockHttpServletRequest();
        acceptedRequest.addHeader("Authorization", "Bearer correct-token");
        var acceptedResponse = new MockHttpServletResponse();

        filter.doFilterInternal(acceptedRequest, acceptedResponse, (request, response) -> accepted.set(true));

        assertThat(accepted).isTrue();
        assertThat(acceptedResponse.getStatus()).isEqualTo(200);

        var rejected = new AtomicBoolean();
        var rejectedRequest = new MockHttpServletRequest();
        rejectedRequest.addHeader("Authorization", "Bearer incorrect-token");
        var rejectedResponse = new MockHttpServletResponse();

        filter.doFilterInternal(rejectedRequest, rejectedResponse, (request, response) -> rejected.set(true));

        assertThat(rejected).isFalse();
        assertThat(rejectedResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void failsClosedWhenNoTokenIsConfigured() throws Exception {
        var invoked = new AtomicBoolean();
        var response = new MockHttpServletResponse();

        filter("")
                .doFilterInternal(
                        new MockHttpServletRequest(), response, (request, chainResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
    }

    private static InternalWorkerTokenFilter filter(String token) {
        return new InternalWorkerTokenFilter(new PdfWorkerProperties(Path.of("ignored"), token, Duration.ofMinutes(2)));
    }
}
