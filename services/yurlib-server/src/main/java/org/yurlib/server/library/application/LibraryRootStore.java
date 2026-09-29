package org.yurlib.server.library.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.yurlib.server.library.domain.LibraryRoot;

public interface LibraryRootStore {

    boolean hasAny();

    LibraryRoot save(LibraryRoot root);

    Optional<LibraryRoot> findById(UUID rootId);

    List<LibraryRoot> findAll();
}
