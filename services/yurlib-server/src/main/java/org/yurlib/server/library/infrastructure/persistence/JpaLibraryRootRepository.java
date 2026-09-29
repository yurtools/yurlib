package org.yurlib.server.library.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaLibraryRootRepository extends JpaRepository<LibraryRootEntity, UUID> {

    List<LibraryRootEntity> findAllByOrderByNameAsc();
}
