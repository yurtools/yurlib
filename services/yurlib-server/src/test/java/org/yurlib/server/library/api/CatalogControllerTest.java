package org.yurlib.server.library.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.yurlib.server.library.application.CatalogQuery;
import org.yurlib.server.library.domain.Asset;

@WebMvcTest(CatalogController.class)
class CatalogControllerTest {

    private static final UUID WORK_ID = UUID.fromString("3a924e93-0c94-4467-a5bb-1813bcf491df");
    private static final UUID ASSET_ID = UUID.fromString("9d889ec8-9ae5-49db-8d75-79d2969dd202");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogQuery catalog;

    @Test
    void returnsTheContractedCatalogPageAndUsesDefaultPagination() throws Exception {
        when(catalog.search(null, 0, 25))
                .thenReturn(new CatalogQuery.CatalogPage(
                        List.of(new CatalogQuery.WorkSummary(
                                WORK_ID,
                                "A Book",
                                true,
                                List.of("An Author"),
                                List.of(new CatalogQuery.AssetSummary(
                                        ASSET_ID, Asset.Format.EPUB, 123, CatalogQuery.Availability.AVAILABLE, true)))),
                        0,
                        25,
                        1));

        mockMvc.perform(get("/api/v1/catalog/works"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items[0].id").value(WORK_ID.toString()))
                .andExpect(jsonPath("$.items[0].title").value("A Book"))
                .andExpect(jsonPath("$.items[0].contributors[0]").value("An Author"))
                .andExpect(jsonPath("$.items[0].provisional").value(true))
                .andExpect(jsonPath("$.items[0].assets[0].id").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.items[0].assets[0].format").value("EPUB"))
                .andExpect(jsonPath("$.items[0].assets[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(catalog).search(null, 0, 25);
    }

    @Test
    void rejectsOutOfContractPaginationAsProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/works").param("page", "-1").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
