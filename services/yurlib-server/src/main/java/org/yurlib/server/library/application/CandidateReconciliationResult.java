package org.yurlib.server.library.application;

public record CandidateReconciliationResult(State state, String errorCode, String safeDiagnostic) {

    public static CandidateReconciliationResult processed() {
        return new CandidateReconciliationResult(State.PROCESSED, null, null);
    }

    public static CandidateReconciliationResult skipped() {
        return new CandidateReconciliationResult(State.SKIPPED, null, null);
    }

    public static CandidateReconciliationResult failed(String errorCode, String safeDiagnostic) {
        return new CandidateReconciliationResult(State.FAILED, errorCode, safeDiagnostic);
    }

    public static CandidateReconciliationResult deferred(String errorCode, String safeDiagnostic) {
        return new CandidateReconciliationResult(State.DEFERRED, errorCode, safeDiagnostic);
    }

    public enum State {
        PROCESSED,
        SKIPPED,
        FAILED,
        DEFERRED
    }
}
