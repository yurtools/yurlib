package org.yurlib.server.library.application;

import java.util.UUID;

public interface CoverContentUseCases {

    OpenedCoverContent open(UUID workId);
}
