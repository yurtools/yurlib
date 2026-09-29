package org.yurlib.server.library.application;

import java.util.Optional;
import java.util.UUID;

public interface AssetContentStore {

    Optional<AssetContentLocation> findByAssetId(UUID assetId);
}
