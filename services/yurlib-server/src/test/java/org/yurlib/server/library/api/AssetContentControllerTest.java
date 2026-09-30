package org.yurlib.server.library.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.yurlib.server.library.application.AssetContentFailure;
import org.yurlib.server.library.application.OpenedAssetContent;
import org.yurlib.server.library.application.OriginalAssetContentUseCases;
import org.yurlib.server.library.domain.Asset;

@WebMvcTest(AssetContentController.class)
class AssetContentControllerTest {

    private static final UUID ASSET_ID = UUID.fromString("9d889ec8-9ae5-49db-8d75-79d2969dd202");
    private static final String CORRELATION_ID = "f0e9df26-0d17-42eb-a2f8-03057e1ee336";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OriginalAssetContentUseCases contentUseCases;

    @Test
    void streamsTheOriginalWithAHeaderSafeFilename() throws Exception {
        var bytes = new byte[] {1, 2, 3, 4};
        when(contentUseCases.open(ASSET_ID))
                .thenReturn(new OpenedAssetContent(
                        ASSET_ID,
                        Asset.Format.EPUB,
                        bytes.length,
                        "unsafe\";\r\nInjected: yes.epub",
                        new ByteArrayInputStream(bytes)));

        mockMvc.perform(get("/api/v1/assets/{assetId}/content", ASSET_ID)
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/epub+zip"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, bytes.length))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, not(containsString("\r"))))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, not(containsString("\n"))))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, not(containsString("Injected:"))));
    }

    @Test
    void servesEachNewFormatWithItsRegisteredMediaType() throws Exception {
        assertMediaType(Asset.Format.PDF, "book.pdf", "application/pdf");
        assertMediaType(
                Asset.Format.DOCX,
                "book.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertMediaType(Asset.Format.DJVU, "book.djvu", "image/vnd.djvu");
    }

    @Test
    void mapsUnknownAndChangedAssetsToStableProblemCodes() throws Exception {
        when(contentUseCases.open(ASSET_ID))
                .thenThrow(new AssetContentFailure(
                        AssetContentFailure.Code.ASSET_NOT_FOUND, "The requested original asset does not exist."));

        mockMvc.perform(get("/api/v1/assets/{assetId}/content", ASSET_ID)
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));

        reset(contentUseCases);
        when(contentUseCases.open(ASSET_ID))
                .thenThrow(new AssetContentFailure(
                        AssetContentFailure.Code.FILE_UNSTABLE,
                        "The requested original asset changed after it was cataloged."));

        mockMvc.perform(get("/api/v1/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_UNSTABLE"));
    }

    @Test
    void rejectsAnInvalidAssetIdentifierAsProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/assets/not-a-uuid/content"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    private void assertMediaType(Asset.Format format, String filename, String mediaType) throws Exception {
        var bytes = new byte[] {1};
        when(contentUseCases.open(ASSET_ID))
                .thenReturn(new OpenedAssetContent(
                        ASSET_ID, format, bytes.length, filename, new ByteArrayInputStream(bytes)));

        mockMvc.perform(get("/api/v1/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(mediaType))
                .andExpect(content().bytes(bytes));
    }
}
