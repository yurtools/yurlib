package org.yurlib.server.library.application;

import java.util.UUID;

public interface ConversionUseCases {

    ConversionJob request(UUID sourceAssetId, ConversionRoute route);

    ConversionJob find(UUID jobId);

    ConversionJob cancel(UUID jobId, long expectedVersion);
}
