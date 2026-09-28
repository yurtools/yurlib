package org.yurlib.server.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemController.class)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsReadyStatus() throws Exception {
        mockMvc.perform(get("/api/v1/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Yurlib"))
                .andExpect(jsonPath("$.status").value("ready"));
    }
}
