package org.yurlib.server.library.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.yurlib.server.library.application.CoverContentUseCases;
import org.yurlib.server.library.application.OpenedCoverContent;

@WebMvcTest(CoverContentController.class)
class CoverContentControllerTest {

    private static final UUID WORK_ID = UUID.fromString("f76a78cb-5b7c-4211-a024-0f91922137b2");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CoverContentUseCases contentUseCases;

    @Test
    void servesAContentAddressedCoverWithPrivateImmutableCaching() throws Exception {
        var bytes = new byte[] {1, 2, 3, 4};
        when(contentUseCases.open(WORK_ID))
                .thenReturn(new OpenedCoverContent(
                        new ByteArrayInputStream(bytes), bytes.length, "image/jpeg", "\"sha256-value\""));

        mockMvc.perform(get("/api/v1/catalog/works/{workId}/cover", WORK_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/jpeg"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, bytes.length))
                .andExpect(header().string(HttpHeaders.ETAG, "\"sha256-value\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, private, immutable"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
