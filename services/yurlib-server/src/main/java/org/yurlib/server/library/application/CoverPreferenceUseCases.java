package org.yurlib.server.library.application;

import java.util.UUID;

public interface CoverPreferenceUseCases {

    CoverPreference select(UUID workId, UUID sourceAssetId, String reason, long expectedVersion, UUID actorId);

    record CoverPreference(UUID workId, UUID sourceAssetId, long version) {}
}
