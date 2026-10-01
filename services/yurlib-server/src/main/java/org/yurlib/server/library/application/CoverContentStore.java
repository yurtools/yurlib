package org.yurlib.server.library.application;

import java.util.Optional;
import java.util.UUID;

public interface CoverContentStore {

    Optional<CoverContentLocation> findByWorkId(UUID workId);
}
