package org.yurlib.server.library.api;

import java.time.Instant;
import java.util.UUID;
import org.yurlib.server.library.domain.LibraryRoot;

public record LibraryRootResponse(
        UUID id,
        String name,
        String mountAlias,
        String relativePath,
        String mode,
        String availability,
        Instant lastSuccessfulScanAt) {

    static LibraryRootResponse from(LibraryRoot root) {
        return new LibraryRootResponse(
                root.id(),
                root.name(),
                root.mountAlias(),
                root.relativeBasePath(),
                root.mode().name(),
                root.availability().name(),
                root.lastSuccessfulScanAt());
    }
}
