package org.yurlib.server.library.application;

import java.util.List;
import java.util.UUID;
import org.yurlib.server.library.domain.LibraryRoot;

public interface LibraryRootUseCases {

    LibraryRoot configure(ConfigureLibraryRootCommand command);

    List<LibraryRoot> list();

    void remove(UUID rootId);
}
