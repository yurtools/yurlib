package org.yurlib.server.library.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "The compact constructor deep-copies every collection and nested observation list.")
public record ExtractedBookMetadata(
        Format format,
        String title,
        List<String> contributors,
        String language,
        Map<String, String> identifiers,
        Map<String, List<String>> additionalObservations,
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
        additionalObservations = additionalObservations.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        requireParserValue(parserName, "parserName");
        requireParserValue(parserVersion, "parserVersion");
    }

    public ExtractedBookMetadata(
            Format format,
            String title,
            List<String> contributors,
            String language,
            Map<String, String> identifiers,
            long byteSize,
            Instant modifiedAt,
            String parserName,
            String parserVersion) {
        this(
                format,
                title,
                contributors,
                language,
                identifiers,
                Map.of(),
                byteSize,
                modifiedAt,
                parserName,
                parserVersion);
    }

    private static void requireParserValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public enum Format {
        EPUB,
        FB2,
        MOBI,
        PDF,
        DOCX,
        DJVU
    }
}
