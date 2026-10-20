package org.yurlib.server.library.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.LibraryRootFailure;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.domain.LibraryRoot;

@Repository
public class LibraryRootPersistenceAdapter implements LibraryRootStore {

    private final JpaLibraryRootRepository repository;

    public LibraryRootPersistenceAdapter(JpaLibraryRootRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean hasAny() {
        return repository.count() > 0;
    }

    @Override
    public LibraryRoot save(LibraryRoot root) {
        return repository.saveAndFlush(new LibraryRootEntity(root)).toDomain();
    }

    @Override
    public Optional<LibraryRoot> findById(UUID rootId) {
        return repository.findById(rootId).map(LibraryRootEntity::toDomain);
    }

    @Override
    public List<LibraryRoot> findAll() {
        return repository.findAllByOrderByNameAsc().stream()
                .map(LibraryRootEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public LibraryRoot setDefaultOutput(UUID rootId, LibraryRoot.OutputPurpose purpose) {
        var roots = repository.findAllForDefaultUpdate().stream()
                .map(LibraryRootEntity::toDomain)
                .toList();
        var target = roots.stream()
                .filter(root -> root.id().equals(rootId))
                .findFirst()
                .orElseThrow(() -> new LibraryRootFailure(
                        LibraryRootFailure.Code.ROOT_NOT_FOUND, "The requested library root does not exist."));
        if (target.mode() != LibraryRoot.Mode.MANAGED_OUTPUT) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_MODE_INVALID, "Choose an output folder for generated files.");
        }
        if (target.availability() != LibraryRoot.Availability.AVAILABLE) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_UNAVAILABLE,
                    "The output folder is unavailable. Reconnect it before selecting it.");
        }
        for (var root : roots) {
            if (!root.id().equals(rootId)) {
                var cleared = root.withOutputDefaults(
                        purpose != LibraryRoot.OutputPurpose.COVERS && root.defaultForCovers(),
                        purpose != LibraryRoot.OutputPurpose.CONVERSIONS && root.defaultForConversions());
                if (!cleared.equals(root)) {
                    save(cleared);
                }
            }
        }
        return save(target.withOutputDefaults(
                purpose == LibraryRoot.OutputPurpose.COVERS || target.defaultForCovers(),
                purpose == LibraryRoot.OutputPurpose.CONVERSIONS || target.defaultForConversions()));
    }

    @Override
    public void delete(UUID rootId) {
        try {
            repository.deleteById(rootId);
            repository.flush();
        } catch (DataIntegrityViolationException failure) {
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_IN_USE,
                    "A library root with catalog or job history cannot be removed.",
                    failure);
        }
    }
}
