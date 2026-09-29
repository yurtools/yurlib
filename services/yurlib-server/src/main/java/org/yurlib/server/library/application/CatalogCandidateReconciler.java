package org.yurlib.server.library.application;

import java.util.UUID;

public interface CatalogCandidateReconciler {

    CandidateReconciliationResult reconcile(
            UUID rootId, UUID scanJobId, String extractionVersion, ScanDiscovery.Candidate candidate);
}
