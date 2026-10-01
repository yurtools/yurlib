package org.yurlib.server.library.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.yurlib.server.library.application.CatalogCuration;
import org.yurlib.server.library.application.CatalogCurationFailure;
import org.yurlib.server.library.application.LibraryAccessContext;

@WebMvcTest(CatalogCurationController.class)
class CatalogCurationControllerTest {

    private static final UUID WORK_ID = UUID.fromString("3a924e93-0c94-4467-a5bb-1813bcf491df");
    private static final UUID ACTOR_ID = UUID.fromString("169a5a95-1f91-4bcb-8af6-1bc72de37292");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogCuration curation;

    @MockitoBean
    private LibraryAccessContext accessContext;

    @Test
    void updatesATitleWithActorReasonAndExpectedVersion() throws Exception {
        when(accessContext.current()).thenReturn(new LibraryAccessContext.Access(ACTOR_ID, false));
        when(curation.updateTitle(WORK_ID, "Corrected", "Verified cover", 2, ACTOR_ID))
                .thenReturn(work("Corrected", 3));

        mockMvc.perform(put("/api/v1/curation/works/{workId}/title", WORK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":"Corrected","reason":"Verified cover","expectedVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title.value").value("Corrected"))
                .andExpect(jsonPath("$.title.overrideVersion").value(3));

        verify(curation).updateTitle(WORK_ID, "Corrected", "Verified cover", 2, ACTOR_ID);
    }

    @Test
    void returnsAStableConflictProblemForAStaleEdit() throws Exception {
        when(accessContext.current()).thenReturn(new LibraryAccessContext.Access(ACTOR_ID, false));
        when(curation.updateTitle(any(), any(), any(), anyLong(), any()))
                .thenThrow(new CatalogCurationFailure(
                        CatalogCurationFailure.Code.VERSION_CONFLICT,
                        "The catalog record changed. Reload it before saving again."));

        mockMvc.perform(put("/api/v1/curation/works/{workId}/title", WORK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":"Corrected","reason":"Verified cover","expectedVersion":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("The catalog record changed. Reload it before saving again."));
    }

    private static CatalogCuration.WorkCuration work(String title, long overrideVersion) {
        return new CatalogCuration.WorkCuration(
                WORK_ID,
                0,
                new CatalogCuration.MetadataField(title, "CURATED", overrideVersion, List.of("Observed"), List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
