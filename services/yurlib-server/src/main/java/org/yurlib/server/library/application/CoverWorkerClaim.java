package org.yurlib.server.library.application;

import java.util.UUID;
import org.yurlib.server.library.domain.Asset;

public record CoverWorkerClaim(UUID id, UUID leaseToken, long byteSize, String sha256, Asset.Format format) {}
