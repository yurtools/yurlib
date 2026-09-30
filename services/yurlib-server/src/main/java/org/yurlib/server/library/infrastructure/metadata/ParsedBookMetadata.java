package org.yurlib.server.library.infrastructure.metadata;

import java.util.List;
import java.util.Map;
import org.yurlib.server.library.application.ExtractedBookMetadata;

record ParsedBookMetadata(
        ExtractedBookMetadata.Format format,
        String title,
        List<String> contributors,
        String language,
        Map<String, String> identifiers,
        Map<String, List<String>> additionalObservations,
        String parserName,
        String parserVersion) {

    ParsedBookMetadata {
        contributors = List.copyOf(contributors);
        identifiers = Map.copyOf(identifiers);
        additionalObservations = additionalObservations.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    ParsedBookMetadata(
            ExtractedBookMetadata.Format format,
            String title,
            List<String> contributors,
            String language,
            Map<String, String> identifiers,
            String parserName,
            String parserVersion) {
        this(format, title, contributors, language, identifiers, Map.of(), parserName, parserVersion);
    }
}
