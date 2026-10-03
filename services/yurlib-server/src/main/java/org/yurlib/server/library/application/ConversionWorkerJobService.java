package org.yurlib.server.library.application;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public interface ConversionWorkerJobService {

    Optional<ConversionWorkerClaim> claim();

    boolean heartbeat(UUID jobId, UUID leaseToken);

    Path leasedInput(UUID jobId, UUID leaseToken);

    void upload(UUID jobId, UUID leaseToken, long byteSize, String sha256, InputStream content);

    void complete(UUID jobId, UUID leaseToken, ConversionWorkerResult result);
}
