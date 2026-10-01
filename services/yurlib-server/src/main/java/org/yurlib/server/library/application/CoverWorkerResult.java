package org.yurlib.server.library.application;

public record CoverWorkerResult(
        State state,
        SelectionKind selectionKind,
        String sourceLocator,
        String inputSha256,
        String processorName,
        String processorVersion,
        Integer width,
        Integer height,
        String mediaType,
        String outputBase64,
        String errorCode,
        String safeDiagnostic) {

    public enum State {
        READY,
        UNAVAILABLE,
        FAILED_SAFE
    }

    public enum SelectionKind {
        DECLARED_EMBEDDED,
        DOCX_THUMBNAIL,
        PDF_PAGE_ONE,
        DJVU_PAGE_ONE
    }
}
