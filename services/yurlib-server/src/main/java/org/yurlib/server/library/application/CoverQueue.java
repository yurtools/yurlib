package org.yurlib.server.library.application;

import java.nio.file.Path;
import java.util.UUID;
import org.yurlib.server.library.domain.Asset;

public interface CoverQueue {

    void stageAndQueue(UUID sourceAssetId, Path sourceFile, Asset.Format format, long expectedSize);
}
