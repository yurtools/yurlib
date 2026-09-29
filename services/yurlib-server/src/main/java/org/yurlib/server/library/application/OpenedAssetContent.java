package org.yurlib.server.library.application;

import java.io.InputStream;
import java.util.UUID;
import org.yurlib.server.library.domain.Asset;

public record OpenedAssetContent(
        UUID assetId, Asset.Format format, long byteSize, String sourceFilename, InputStream content) {}
