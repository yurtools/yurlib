package org.yurlib.server.library.application;

import org.yurlib.server.library.domain.Asset;

public enum ConversionRoute {
    FB2_TO_EPUB_V1(Asset.Format.FB2, "calibre-9.15.0-fb2-epub-v1"),
    MOBI_TO_EPUB_V1(Asset.Format.MOBI, "calibre-9.15.0-mobi-epub-v1");

    private final Asset.Format sourceFormat;
    private final String version;

    ConversionRoute(Asset.Format sourceFormat, String version) {
        this.sourceFormat = sourceFormat;
        this.version = version;
    }

    public Asset.Format sourceFormat() {
        return sourceFormat;
    }

    public Asset.Format targetFormat() {
        return Asset.Format.EPUB;
    }

    public String version() {
        return version;
    }

    public String effectiveSettings() {
        return "{\"epubVersion\":3,\"defaultCover\":false,\"outputProfile\":\"generic_eink\"}";
    }
}
