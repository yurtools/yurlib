package org.yurlib.server.library.application;

import java.util.UUID;

public interface LibraryRootAccess {

    boolean isAllowed(UUID rootId);
}
