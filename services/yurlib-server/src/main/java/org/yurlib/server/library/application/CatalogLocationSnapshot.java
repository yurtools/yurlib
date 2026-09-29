package org.yurlib.server.library.application;

import java.time.Instant;

public record CatalogLocationSnapshot(long byteSize, Instant modifiedAt, String extractionVersion) {}
