package org.yurlib.server.library.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.yurlib.server.library.application.AllowedMountQuery;

@WebMvcTest(LibraryMountController.class)
class LibraryMountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AllowedMountQuery mounts;

    @Test
    void advertisesOnlyConfiguredAliases() throws Exception {
        when(mounts.aliases()).thenReturn(List.of("archive", "main"));

        mockMvc.perform(get("/api/v1/library-mounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alias").value("archive"))
                .andExpect(jsonPath("$[1].alias").value("main"))
                .andExpect(jsonPath("$[0].path").doesNotExist());
    }
}
