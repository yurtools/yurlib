package org.yurlib.server.library.application;

import java.util.UUID;

public interface OriginalAssetContentUseCases {

    OpenedAssetContent open(UUID assetId);
}
