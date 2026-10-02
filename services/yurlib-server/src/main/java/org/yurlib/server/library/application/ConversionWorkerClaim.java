package org.yurlib.server.library.application;

import java.util.UUID;
import org.yurlib.server.library.domain.Asset;

public record ConversionWorkerClaim(
        UUID id,
        UUID leaseToken,
        long byteSize,
        String sha256,
        Asset.Format sourceFormat,
        ConversionRoute route,
        String routeVersion,
        String effectiveSettings,
        long deadlineSeconds) {}
