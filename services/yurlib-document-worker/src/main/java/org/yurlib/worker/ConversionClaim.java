package org.yurlib.worker;

import java.util.UUID;

record ConversionClaim(
        UUID id,
        UUID leaseToken,
        long byteSize,
        String sha256,
        SourceFormat sourceFormat,
        Route route,
        String routeVersion,
        String effectiveSettings,
        long deadlineSeconds) {

    enum SourceFormat {
        FB2,
        MOBI
    }

    enum Route {
        FB2_TO_EPUB_V1,
        MOBI_TO_EPUB_V1
    }
}
