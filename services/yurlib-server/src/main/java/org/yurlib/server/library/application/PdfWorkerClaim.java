package org.yurlib.server.library.application;

import java.util.UUID;

public record PdfWorkerClaim(UUID id, UUID leaseToken, long byteSize, String sha256) {}
