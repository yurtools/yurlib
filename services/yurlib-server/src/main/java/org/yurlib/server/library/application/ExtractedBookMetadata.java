package org.yurlib.server.library.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExtractedBookMetadata(
        Format format,
        String title,
        List<String> contributors,
        String language,
        Map<String, String> identifiers,
        long byteSize,
        Instant modifiedAt,
        String parserName,
        String parserVersion) {

    public ExtractedBookMetadata {
        if (format == null || byteSize < 0 || modifiedAt == null) {
            throw new IllegalArgumentException("format, non-negative byteSize, and modifiedAt are required");
        }
        contributors = List.copyOf(contributors);
        identifiers = Map.copyOf(identifiers);
        requireParserValue(parserName, "parserName");
        requireParserValue(parserVersion, "parserVersion");
    }

    private static void requireParserValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public enum Format {
        EPUB,
        FB2,
        MOBI
    }
}
