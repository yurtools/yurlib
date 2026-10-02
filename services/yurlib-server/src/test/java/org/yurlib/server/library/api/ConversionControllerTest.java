package org.yurlib.server.library.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.yurlib.server.library.application.ConversionFailure;
import org.yurlib.server.library.application.ConversionJob;
import org.yurlib.server.library.application.ConversionRoute;
import org.yurlib.server.library.application.ConversionUseCases;

@WebMvcTest(ConversionController.class)
class ConversionControllerTest {

    private static final UUID ASSET_ID = UUID.fromString("dce52f92-c0cd-4728-a3ad-5ed76478ab96");
    private static final UUID JOB_ID = UUID.fromString("e08fb5bc-48f9-4fac-992b-5ceea6685a3d");
    private static final Instant CREATED_AT = Instant.parse("2026-10-02T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversionUseCases conversions;

    @Test
    void queuesASupportedConversion() throws Exception {
        when(conversions.request(ASSET_ID, ConversionRoute.FB2_TO_EPUB_V1)).thenReturn(job());

        mockMvc.perform(post("/api/v1/assets/{assetId}/conversions", ASSET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"route\":\"FB2_TO_EPUB_V1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.route").value("FB2_TO_EPUB_V1"))
                .andExpect(jsonPath("$.state").value("QUEUED"));
    }

    @Test
    void readsAndCancelsWithOptimisticVersion() throws Exception {
        when(conversions.find(JOB_ID)).thenReturn(job());
        when(conversions.cancel(JOB_ID, 0)).thenReturn(cancelledJob());

        mockMvc.perform(get("/api/v1/conversions/{jobId}", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        mockMvc.perform(post("/api/v1/conversions/{jobId}/cancel", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void returnsSafeProblemDetailsForUnsupportedRoutes() throws Exception {
        when(conversions.request(ASSET_ID, ConversionRoute.FB2_TO_EPUB_V1))
                .thenThrow(new ConversionFailure(
                        ConversionFailure.Code.ROUTE_NOT_SUPPORTED,
                        "The requested conversion route is not supported."));

        mockMvc.perform(post("/api/v1/assets/{assetId}/conversions", ASSET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"route\":\"FB2_TO_EPUB_V1\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_SUPPORTED"));
    }

    private static ConversionJob job() {
        return new ConversionJob(
                JOB_ID,
                ASSET_ID,
                null,
                ConversionRoute.FB2_TO_EPUB_V1,
                ConversionJob.State.QUEUED,
                0,
                false,
                null,
                null,
                CREATED_AT,
                null,
                null,
                0);
    }

    private static ConversionJob cancelledJob() {
        return new ConversionJob(
                JOB_ID,
                ASSET_ID,
                null,
                ConversionRoute.FB2_TO_EPUB_V1,
                ConversionJob.State.CANCELLED,
                0,
                true,
                null,
                null,
                CREATED_AT,
                null,
                CREATED_AT,
                1);
    }
}
