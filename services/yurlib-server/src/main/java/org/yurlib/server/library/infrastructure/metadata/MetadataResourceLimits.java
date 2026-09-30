package org.yurlib.server.library.infrastructure.metadata;

import java.time.Duration;

record MetadataResourceLimits(
        long maximumSourceBytes,
        long maximumBytesRead,
        int maximumXmlBytes,
        int maximumArchiveEntries,
        int maximumArchiveDirectoryBytes,
        int maximumSelectedValueBytes,
        int maximumStructuralDepth,
        int maximumOpenFiles,
        Duration maximumElapsed) {

    private static final long MEBIBYTE = 1024L * 1024;

    MetadataResourceLimits {
        if (maximumSourceBytes <= 0
                || maximumBytesRead <= 0
                || maximumXmlBytes <= 0
                || maximumArchiveEntries <= 0
                || maximumArchiveDirectoryBytes <= 0
                || maximumSelectedValueBytes <= 0
                || maximumStructuralDepth <= 0
                || maximumOpenFiles <= 0
                || maximumElapsed == null
                || maximumElapsed.isNegative()
                || maximumElapsed.isZero()) {
            throw new IllegalArgumentException("Metadata resource limits must be positive.");
        }
    }

    static MetadataResourceLimits m2Defaults() {
        return new MetadataResourceLimits(
                4L * 1024 * MEBIBYTE,
                64L * MEBIBYTE,
                4 * 1024 * 1024,
                10_000,
                4 * 1024 * 1024,
                64 * 1024,
                128,
                4,
                Duration.ofSeconds(30));
    }
}
