package org.yurlib.server.library.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface JpaLibraryRootRepository extends JpaRepository<LibraryRootEntity, UUID> {

    List<LibraryRootEntity> findAllByOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select root from LibraryRootEntity root order by root.id")
    List<LibraryRootEntity> findAllForDefaultUpdate();
}
