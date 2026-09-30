package org.yurlib.server.library.application;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public interface PdfWorkerJobService {

    Optional<PdfWorkerClaim> claim();

    Path leasedInput(UUID jobId, UUID leaseToken);

    void complete(UUID jobId, UUID leaseToken, PdfWorkerResult result);
}
