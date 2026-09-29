package org.yurlib.server.library.application;

import java.util.List;
import org.yurlib.server.library.domain.LibraryRoot;

public interface LibraryRootStore {

    boolean hasAny();

    LibraryRoot save(LibraryRoot root);

    List<LibraryRoot> findAll();
}
