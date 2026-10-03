package org.yurlib.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversionRouteContractTest {

    private static final String SETTINGS =
            "{\"epubVersion\":3,\"defaultCover\":false,\"outputProfile\":\"generic_eink\"}";

    @Test
    void acceptsOnlyThePinnedFb2Contract() {
        assertThat(YurlibDocumentWorker.supports(claim(
                        ConversionClaim.SourceFormat.FB2,
                        ConversionClaim.Route.FB2_TO_EPUB_V1,
                        "calibre-9.15.0-fb2-epub-v1",
                        SETTINGS)))
                .isTrue();
        assertThat(YurlibDocumentWorker.supports(claim(
                        ConversionClaim.SourceFormat.MOBI,
                        ConversionClaim.Route.FB2_TO_EPUB_V1,
                        "calibre-9.15.0-fb2-epub-v1",
                        SETTINGS)))
                .isFalse();
    }

    @Test
    void acceptsOnlyThePinnedMobiContractAndEffectiveSettings() {
        assertThat(YurlibDocumentWorker.supports(claim(
                        ConversionClaim.SourceFormat.MOBI,
                        ConversionClaim.Route.MOBI_TO_EPUB_V1,
                        "calibre-9.15.0-mobi-epub-v1",
                        SETTINGS)))
                .isTrue();
        assertThat(YurlibDocumentWorker.supports(claim(
                        ConversionClaim.SourceFormat.MOBI,
                        ConversionClaim.Route.MOBI_TO_EPUB_V1,
                        "calibre-9.15.0-mobi-epub-v1",
                        "{}")))
                .isFalse();
    }

    private static ConversionClaim claim(
            ConversionClaim.SourceFormat source, ConversionClaim.Route route, String version, String settings) {
        return new ConversionClaim(
                UUID.randomUUID(), UUID.randomUUID(), 1, "0".repeat(64), source, route, version, settings, 600);
    }
}
