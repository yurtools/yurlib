package org.yurlib.server.library.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.yurlib.server.library.application.CatalogRecovery;
import org.yurlib.server.library.application.CatalogRecoveryFailure;
import org.yurlib.server.library.application.LibraryAccessContext;

@WebMvcTest(CatalogRecoveryController.class)
class CatalogRecoveryControllerTest {

    private static final UUID ACTOR_ID = UUID.fromString("c8c4e5d7-e1d4-4b7c-a319-6ad8767fc775");
    private static final UUID SURVIVOR_ID = UUID.fromString("21cae7c7-4f67-4cf1-9678-148178276ceb");
    private static final UUID SOURCE_ID = UUID.fromString("0128c287-a03c-40ce-9626-069789a686f3");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogRecovery recovery;

    @MockitoBean
    private LibraryAccessContext accessContext;

    @Test
    void previewsAndAppliesAnActorAttributedMerge() throws Exception {
        var preview = new CatalogRecovery.RecoveryPreview(
                CatalogRecovery.SubjectType.WORK,
                new CatalogRecovery.SubjectSummary(SURVIVOR_ID, "Survivor", 2),
                new CatalogRecovery.SubjectSummary(SOURCE_ID, "Source", 3),
                new CatalogRecovery.Impact(1, 2, 4, 1, 3, 1, 2, 0),
                true,
                List.of());
        var idempotencyKey = UUID.randomUUID();
        var operation = new CatalogRecovery.MergeOperation(
                UUID.randomUUID(),
                CatalogRecovery.SubjectType.WORK,
                SURVIVOR_ID,
                SOURCE_ID,
                "APPLIED",
                ACTOR_ID,
                "Verified duplicate",
                Instant.parse("2026-10-02T12:00:00Z"),
                null);
        when(accessContext.current()).thenReturn(new LibraryAccessContext.Access(ACTOR_ID, false));
        when(recovery.preview(CatalogRecovery.SubjectType.WORK, SURVIVOR_ID, SOURCE_ID))
                .thenReturn(preview);
        when(recovery.merge(
                        CatalogRecovery.SubjectType.WORK,
                        SURVIVOR_ID,
                        SOURCE_ID,
                        2,
                        3,
                        idempotencyKey,
                        "Verified duplicate",
                        ACTOR_ID))
                .thenReturn(operation);

        mockMvc.perform(get("/api/v1/curation/recovery/preview")
                        .param("subjectType", "WORK")
                        .param("survivorId", SURVIVOR_ID.toString())
                        .param("sourceId", SOURCE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impact.observations").value(4))
                .andExpect(jsonPath("$.mergeAllowed").value(true));
        mockMvc.perform(post("/api/v1/curation/recovery/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "subjectType":"WORK",
                                  "survivorId":"%s",
                                  "sourceId":"%s",
                                  "survivorVersion":2,
                                  "sourceVersion":3,
                                  "idempotencyKey":"%s",
                                  "reason":"Verified duplicate"
                                }
                                """.formatted(SURVIVOR_ID, SOURCE_ID, idempotencyKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(operation.id().toString()))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        verify(recovery)
                .merge(
                        CatalogRecovery.SubjectType.WORK,
                        SURVIVOR_ID,
                        SOURCE_ID,
                        2,
                        3,
                        idempotencyKey,
                        "Verified duplicate",
                        ACTOR_ID);
    }

    @Test
    void returnsAStableConflictWhenAutomaticUndoIsUnsafe() throws Exception {
        var operationId = UUID.randomUUID();
        when(accessContext.current()).thenReturn(new LibraryAccessContext.Access(ACTOR_ID, false));
        when(recovery.undo(operationId, "Undo", ACTOR_ID))
                .thenThrow(new CatalogRecoveryFailure(
                        CatalogRecoveryFailure.Code.SPLIT_CONFLICT,
                        "Automatic undo is unsafe because the merged catalog state has changed."));

        mockMvc.perform(post("/api/v1/curation/recovery/merges/{operationId}/undo", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Undo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPLIT_CONFLICT"));
    }
}
