package org.yurlib.server.library.application;

import java.util.UUID;

public record CoverContentLocation(
        UUID workId,
        UUID rootId,
        String normalizedRelativePath,
        String mediaType,
        long byteSize,
        String outputSha256) {}
