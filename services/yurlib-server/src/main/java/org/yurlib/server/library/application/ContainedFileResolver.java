package org.yurlib.server.library.application;

import java.nio.file.Path;
import org.yurlib.server.library.domain.LibraryRoot;

public interface ContainedFileResolver {

    Path resolveContainedFile(LibraryRoot root, String normalizedRelativePath);
}
