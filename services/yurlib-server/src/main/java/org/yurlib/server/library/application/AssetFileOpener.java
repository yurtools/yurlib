package org.yurlib.server.library.application;

import org.yurlib.server.library.domain.LibraryRoot;

public interface AssetFileOpener {

    OpenedAssetContent open(LibraryRoot root, AssetContentLocation location);
}
