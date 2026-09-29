package org.yurlib.server.library.infrastructure.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yurlib.library")
public record LibraryStorageProperties(List<Mount> mounts) {

    public LibraryStorageProperties {
        mounts = mounts == null ? List.of() : List.copyOf(mounts);
    }

    public record Mount(String alias, Path path) {}
}
