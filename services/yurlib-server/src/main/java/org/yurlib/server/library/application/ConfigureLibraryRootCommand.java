package org.yurlib.server.library.application;

import org.yurlib.server.library.domain.LibraryRoot;

public record ConfigureLibraryRootCommand(
        String name,
        String mountAlias,
        String relativePath,
        String identityToken,
        LibraryRoot.Mode mode,
        boolean defaultForCovers) {

    public ConfigureLibraryRootCommand(
            String name, String mountAlias, String relativePath, String identityToken, LibraryRoot.Mode mode) {
        this(name, mountAlias, relativePath, identityToken, mode, false);
    }

    public ConfigureLibraryRootCommand(String name, String mountAlias, String relativePath, String identityToken) {
        this(name, mountAlias, relativePath, identityToken, LibraryRoot.Mode.READ_ONLY_SOURCE, false);
    }
}
