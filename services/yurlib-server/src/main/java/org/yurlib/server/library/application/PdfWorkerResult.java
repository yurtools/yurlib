package org.yurlib.server.library.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;

@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "The compact constructor deep-copies every collection and nested observation list.")
public record PdfWorkerResult(
        State state,
        String title,
        List<String> contributors,
        String language,
        Map<String, String> identifiers,
        Map<String, List<String>> observations,
        Integer pageCount,
        String parserName,
        String parserVersion,
        String errorCode,
        String safeDiagnostic) {

    public PdfWorkerResult {
        contributors = contributors == null ? List.of() : List.copyOf(contributors);
        identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
        observations = observations == null
                ? Map.of()
                : observations.entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public enum State {
        EXTRACTED,
        FAILED
    }
}
