package org.yurlib.worker;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;

@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "The compact constructor deep-copies every collection and nested observation list.")
public record PdfMetadataResult(
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

    public PdfMetadataResult {
        contributors = contributors == null ? List.of() : List.copyOf(contributors);
        identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
        observations = observations == null
                ? Map.of()
                : observations.entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    static PdfMetadataResult extracted(
            String title,
            List<String> contributors,
            String language,
            Map<String, String> identifiers,
            Map<String, List<String>> observations,
            int pageCount) {
        return new PdfMetadataResult(
                State.EXTRACTED,
                title,
                contributors,
                language,
                identifiers,
                observations,
                pageCount,
                "apache-pdfbox",
                "3.0.8-1",
                null,
                null);
    }

    static PdfMetadataResult failed(String code, String diagnostic) {
        return new PdfMetadataResult(
                State.FAILED,
                null,
                List.of(),
                null,
                Map.of(),
                Map.of(),
                null,
                "apache-pdfbox",
                "3.0.8-1",
                code,
                diagnostic);
    }

    public enum State {
        EXTRACTED,
        FAILED
    }
}
