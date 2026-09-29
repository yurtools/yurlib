package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;

public record AssetLocation(
        UUID id,
        UUID assetId,
        UUID libraryRootId,
        String normalizedRelativePath,
        long byteSize,
        Instant modifiedAt,
        String fileKey,
        Availability availability,
        UUID lastSeenScanId) {

    public AssetLocation {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(assetId, "assetId");
        DomainAssertions.required(libraryRootId, "libraryRootId");
        DomainAssertions.normalizedRelativePath(normalizedRelativePath, "normalizedRelativePath", false);
        DomainAssertions.nonNegative(byteSize, "byteSize");
        DomainAssertions.required(modifiedAt, "modifiedAt");
        DomainAssertions.required(availability, "availability");
    }

    public enum Availability {
        AVAILABLE,
        MISSING,
        UNAVAILABLE
    }
}
