package org.yurlib.server.library.application;

import java.util.List;
import java.util.UUID;
import org.yurlib.server.library.domain.LibraryRoot;

public final class ConfigureLibraryRootService implements LibraryRootUseCases {

    private final LibraryRootStore store;
    private final RootLocationVerifier verifier;

    public ConfigureLibraryRootService(LibraryRootStore store, RootLocationVerifier verifier) {
        this.store = store;
        this.verifier = verifier;
    }

    @Override
    public LibraryRoot configure(ConfigureLibraryRootCommand command) {
        if (store.hasAny()) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_ALREADY_CONFIGURED,
                    "A library root is already configured for this deployment.");
        }
        var verified = verifier.verify(command.mountAlias(), command.relativePath(), command.identityToken());
        var root = new LibraryRoot(
                UUID.randomUUID(),
                command.name(),
                command.mountAlias(),
                verified.normalizedRelativePath(),
                verified.identityDigest(),
                LibraryRoot.Mode.READ_ONLY,
                LibraryRoot.Availability.AVAILABLE,
                null);
        return store.save(root);
    }

    @Override
    public List<LibraryRoot> list() {
        return store.findAll();
    }
}
