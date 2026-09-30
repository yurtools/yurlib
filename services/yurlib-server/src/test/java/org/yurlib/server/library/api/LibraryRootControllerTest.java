package org.yurlib.server.library.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.application.LibraryRootUseCases;
import org.yurlib.server.library.domain.LibraryRoot;

@WebMvcTest(LibraryRootController.class)
class LibraryRootControllerTest {

    private static final String CORRELATION_ID = "f0e9df26-0d17-42eb-a2f8-03057e1ee336";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LibraryRootUseCases useCases;

    @Test
    void createsAValidatedRootWithoutReturningTheIdentityTokenOrDigest() throws Exception {
        when(useCases.configure(any())).thenReturn(root());

        mockMvc.perform(post("/api/v1/library-roots")
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Main library",
                                  "mountAlias": "main",
                                  "relativePath": "books",
                                  "identityToken": "private-token-1234",
                                  "mode": "READ_ONLY_SOURCE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(jsonPath("$.mountAlias").value("main"))
                .andExpect(jsonPath("$.relativePath").value("books"))
                .andExpect(jsonPath("$.mode").value("READ_ONLY_SOURCE"))
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.identityToken").doesNotExist())
                .andExpect(jsonPath("$.expectedIdentityDigest").doesNotExist());
    }

    @Test
    void listsConfiguredRoots() throws Exception {
        when(useCases.list()).thenReturn(List.of(root()));

        mockMvc.perform(get("/api/v1/library-roots"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].name").value("Main library"));
    }

    @Test
    void returnsSafeProblemDetailsForAMissingMarker() throws Exception {
        when(useCases.configure(any()))
                .thenThrow(new LibraryRootFailure(
                        LibraryRootFailure.Code.ROOT_UNAVAILABLE, "The configured library root is unavailable."));

        mockMvc.perform(post("/api/v1/library-roots")
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Main library",
                                  "mountAlias": "main",
                                  "relativePath": "books",
                                  "identityToken": "private-token-1234",
                                  "mode": "READ_ONLY_SOURCE"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(jsonPath("$.detail").value("The configured library root is unavailable."));
    }

    @Test
    void returnsProblemDetailsForInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/library-roots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Main library",
                                  "mountAlias": "MAIN",
                                  "relativePath": "books",
                                  "identityToken": "short",
                                  "mode": "READ_ONLY_SOURCE"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void replacesNonUuidCorrelationIdentifiers() throws Exception {
        when(useCases.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/library-roots").header(CorrelationIdFilter.HEADER, "not-safe-to-reflect"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, not("not-safe-to-reflect")))
                .andExpect(header().string(
                                CorrelationIdFilter.HEADER,
                                matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")));
    }

    private static LibraryRoot root() {
        return new LibraryRoot(
                UUID.fromString("238fe729-8360-440e-94a7-012a23ac49f3"),
                "Main library",
                "main",
                "books",
                "a".repeat(64),
                LibraryRoot.Mode.READ_ONLY_SOURCE,
                LibraryRoot.Availability.AVAILABLE,
                null);
    }
}
