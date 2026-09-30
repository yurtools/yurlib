package org.yurlib.server.library.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.yurlib.server.library.domain.LibraryRoot;

public final class ConfigureLibraryRootService implements LibraryRootUseCases {

    private final LibraryRootStore store;
    private final RootLocationVerifier verifier;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The composition root owns these application ports for the service lifetime.")
    public ConfigureLibraryRootService(LibraryRootStore store, RootLocationVerifier verifier) {
        this.store = store;
        this.verifier = verifier;
    }

    @Override
    public LibraryRoot configure(ConfigureLibraryRootCommand command) {
        var verified = verifier.verify(command.mountAlias(), command.relativePath(), command.identityToken());
        rejectOverlap(command.mountAlias(), verified.normalizedRelativePath());
        var root = new LibraryRoot(
                UUID.randomUUID(),
                command.name(),
                command.mountAlias(),
                verified.normalizedRelativePath(),
                verified.identityDigest(),
                command.mode(),
                LibraryRoot.Availability.AVAILABLE,
                null);
        return store.save(root);
    }

    @Override
    public List<LibraryRoot> list() {
        return store.findAll();
    }

    @Override
    public void remove(UUID rootId) {
        if (store.findById(rootId).isEmpty()) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_NOT_FOUND, "The requested library root does not exist.");
        }
        store.delete(rootId);
    }

    private void rejectOverlap(String mountAlias, String relativePath) {
        var overlaps = store.findAll().stream()
                .filter(root -> root.mountAlias().equals(mountAlias))
                .anyMatch(root -> contains(root.relativeBasePath(), relativePath)
                        || contains(relativePath, root.relativeBasePath()));
        if (overlaps) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_OVERLAP, "Library roots on the same mount must not overlap.");
        }
    }

    private static boolean contains(String parent, String child) {
        return parent.isEmpty() || parent.equals(child) || child.startsWith(parent + "/");
    }
}
