package org.yurlib.server.library.infrastructure.persistence;

import java.util.List;
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
        try {
            return repository.saveAndFlush(new LibraryRootEntity(root)).toDomain();
        } catch (DataIntegrityViolationException exception) {
            if (!isSingleRootConstraintViolation(exception)) {
                throw exception;
            }
            throw new LibraryRootFailure(
                    LibraryRootFailure.Code.ROOT_ALREADY_CONFIGURED,
                    "A library root is already configured for this deployment.");
        }
    }

    @Override
    public List<LibraryRoot> findAll() {
        return repository.findAllByOrderByNameAsc().stream().map(LibraryRootEntity::toDomain).toList();
    }

    private static boolean isSingleRootConstraintViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null
                    && current.getMessage().contains("library_root_singleton_unique")) {
                return true;
            }
        }
        return false;
    }
}
