package org.yurlib.server.library.application;

import org.yurlib.server.library.domain.LibraryRoot;

public interface CoverFileOpener {

    OpenedCoverContent open(LibraryRoot root, CoverContentLocation location);
}
