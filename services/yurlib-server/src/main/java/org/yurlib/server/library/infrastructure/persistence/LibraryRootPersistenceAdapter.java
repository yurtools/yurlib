package org.yurlib.server.library.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
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
