package org.yurlib.server.library.application;

import java.util.UUID;

public interface PdfMetadataQueue {

    CandidateReconciliationResult stageAndQueue(
            UUID rootId, UUID scanJobId, String extractionVersion, ScanDiscovery.Candidate candidate);
}
