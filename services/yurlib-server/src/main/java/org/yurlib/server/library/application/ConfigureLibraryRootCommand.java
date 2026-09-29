package org.yurlib.server.library.application;

public record ConfigureLibraryRootCommand(String name, String mountAlias, String relativePath, String identityToken) {}
