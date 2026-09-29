package org.yurlib.server.library.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.yurlib.server.library.application.ScanJobFailure;
import org.yurlib.server.library.application.ScanJobUseCases;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.ScanJob;

@WebMvcTest(ScanJobController.class)
class ScanJobControllerTest {

    private static final UUID ROOT_ID = UUID.fromString("238fe729-8360-440e-94a7-012a23ac49f3");
    private static final UUID JOB_ID = UUID.fromString("77482b0b-12b2-4094-a742-997451491803");
    private static final String CORRELATION_ID = "f0e9df26-0d17-42eb-a2f8-03057e1ee336";
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScanJobUseCases useCases;

    @Test
    void commitsAQueuedJobBeforeReturningAccepted() throws Exception {
        when(useCases.queue(ROOT_ID, CORRELATION_ID)).thenReturn(job(ScanJob.State.QUEUED, 0, false));

        mockMvc.perform(post("/api/v1/library-roots/{rootId}/scans", ROOT_ID)
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isAccepted())
                .andExpect(header().string(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.rootId").value(ROOT_ID.toString()))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.discoveredCount").value(0))
                .andExpect(jsonPath("$.failures").isEmpty());
    }

    @Test
    void returnsDurableCountersAndSafeFileFailures() throws Exception {
        var outcome = new FileOutcome(
                JOB_ID,
                "broken.epub",
                FileOutcome.State.FAILED,
                "FILE_UNREADABLE",
                "A library entry could not be inspected.",
                1,
                CREATED_AT);
        when(useCases.get(JOB_ID))
                .thenReturn(new ScanJobUseCases.ScanJobView(
                        job(ScanJob.State.COMPLETED_WITH_FAILURES, 1, true), List.of(outcome)));

        mockMvc.perform(get("/api/v1/jobs/{jobId}", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED_WITH_FAILURES"))
                .andExpect(jsonPath("$.discoveredCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.coverageComplete").value(true))
                .andExpect(jsonPath("$.failures[0].relativePath").value("broken.epub"))
                .andExpect(jsonPath("$.failures[0].code").value("FILE_UNREADABLE"));
    }

    @Test
    void reportsAnActiveScanConflictAsProblemDetails() throws Exception {
        when(useCases.queue(ROOT_ID, CORRELATION_ID))
                .thenThrow(new ScanJobFailure(
                        ScanJobFailure.Code.SCAN_ALREADY_ACTIVE,
                        "A scan is already queued or running for this library root."));

        mockMvc.perform(post("/api/v1/library-roots/{rootId}/scans", ROOT_ID)
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SCAN_ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    private static ScanJob job(ScanJob.State state, long failures, boolean coverageComplete) {
        var complete = state != ScanJob.State.QUEUED && state != ScanJob.State.RUNNING;
        return new ScanJob(
                JOB_ID,
                ROOT_ID,
                state,
                CORRELATION_ID,
                "discovery-v1",
                CREATED_AT,
                complete ? CREATED_AT : null,
                complete ? CREATED_AT : null,
                complete ? CREATED_AT : null,
                failures,
                0,
                0,
                failures,
                coverageComplete,
                null);
    }
}
