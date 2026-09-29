package org.yurlib.server.testing;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RepositoryPaths {

    private RepositoryPaths() {}

    public static Path root() {
        var candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("contracts/openapi/yurlib-v1.yaml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the Yurlib repository root");
    }
}
