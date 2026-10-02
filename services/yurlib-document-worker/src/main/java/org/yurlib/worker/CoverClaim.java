package org.yurlib.worker;

import java.util.UUID;

record CoverClaim(UUID id, UUID leaseToken, long byteSize, String sha256, Format format) {

    enum Format {
        EPUB,
        FB2,
        MOBI,
        PDF,
        DOCX,
        DJVU
    }
}
