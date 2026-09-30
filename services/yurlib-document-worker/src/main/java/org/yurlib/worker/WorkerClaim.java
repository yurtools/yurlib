package org.yurlib.worker;

import java.util.UUID;

public record WorkerClaim(UUID id, UUID leaseToken, long byteSize, String sha256) {}
