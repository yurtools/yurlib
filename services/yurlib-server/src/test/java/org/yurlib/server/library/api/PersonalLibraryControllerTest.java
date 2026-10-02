package org.yurlib.server.library.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.yurlib.server.library.application.PersonalLibraryUseCases;

@WebMvcTest(PersonalLibraryController.class)
class PersonalLibraryControllerTest {

    private static final UUID WORK_ID = UUID.fromString("2ec53c90-a94c-4766-9d3e-980a1fd98485");
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-02T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PersonalLibraryUseCases personalLibrary;

    @Test
    void returnsTheCurrentUsersPrivateSnapshot() throws Exception {
        when(personalLibrary.snapshot())
                .thenReturn(new PersonalLibraryUseCases.Snapshot(List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/v1/me/library-state"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.favoriteContributors").isArray())
                .andExpect(jsonPath("$.readStates").isArray())
                .andExpect(jsonPath("$.collections").isArray());
    }

    @Test
    void mapsReadStateWithItsOptimisticVersion() throws Exception {
        when(personalLibrary.markRead(WORK_ID, null, COMPLETED_AT, -1))
                .thenReturn(new PersonalLibraryUseCases.ReadState(WORK_ID, null, COMPLETED_AT, 0));

        mockMvc.perform(put("/api/v1/me/works/{workId}/read-state", WORK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "completedEditionId": null,
                                  "completedAt": "2026-10-02T12:00:00Z",
                                  "expectedVersion": -1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workId").value(WORK_ID.toString()))
                .andExpect(jsonPath("$.completedEditionId").value((Object) null))
                .andExpect(jsonPath("$.completedAt").value("2026-10-02T12:00:00Z"))
                .andExpect(jsonPath("$.version").value(0));

        verify(personalLibrary).markRead(WORK_ID, null, COMPLETED_AT, -1);
    }

    @Test
    void rejectsACollectionWithoutAName() throws Exception {
        mockMvc.perform(put("/api/v1/me/collections/{collectionId}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": " ", "ordered": false, "expectedVersion": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
