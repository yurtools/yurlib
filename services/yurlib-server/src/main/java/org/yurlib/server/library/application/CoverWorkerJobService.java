package org.yurlib.server.library.application;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public interface CoverWorkerJobService {

    Optional<CoverWorkerClaim> claim();

    Path leasedInput(UUID jobId, UUID leaseToken);

    void complete(UUID jobId, UUID leaseToken, CoverWorkerResult result);
}
