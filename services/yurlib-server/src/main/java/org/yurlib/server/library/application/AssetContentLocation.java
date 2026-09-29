package org.yurlib.server.library.application;

import java.time.Instant;
import java.util.UUID;
import org.yurlib.server.library.domain.Asset;
import org.yurlib.server.library.domain.AssetLocation;

public record AssetContentLocation(
        UUID assetId,
        UUID rootId,
        String normalizedRelativePath,
        Asset.Format format,
        long byteSize,
        Instant modifiedAt,
        String fileKey,
        AssetLocation.Availability availability) {}
