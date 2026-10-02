package org.yurlib.worker;

record CoverResult(
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

    private static final String PROCESSOR = "yurlib-cover-worker";
    private static final String VERSION = "1";

    static CoverResult ready(
            SelectionKind kind,
            String locator,
            String inputHash,
            int width,
            int height,
            String mediaType,
            String outputBase64) {
        return new CoverResult(
                State.READY,
                kind,
                locator,
                inputHash,
                PROCESSOR,
                VERSION,
                width,
                height,
                mediaType,
                outputBase64,
                null,
                null);
    }

    static CoverResult unavailable(String diagnostic) {
        return failure(State.UNAVAILABLE, "NO_COVER", diagnostic);
    }

    static CoverResult failed(String code, String diagnostic) {
        return failure(State.FAILED_SAFE, code, diagnostic);
    }

    private static CoverResult failure(State state, String code, String diagnostic) {
        return new CoverResult(state, null, null, null, PROCESSOR, VERSION, null, null, null, null, code, diagnostic);
    }

    enum State {
        READY,
        UNAVAILABLE,
        FAILED_SAFE
    }

    enum SelectionKind {
        DECLARED_EMBEDDED,
        DOCX_THUMBNAIL,
        PDF_PAGE_ONE,
        DJVU_PAGE_ONE
    }
}
