package org.yurlib.server.library.domain;

import java.util.UUID;

public record AssetDerivationSource(UUID derivedAssetId, UUID sourceAssetId, int sourceOrdinal, String purpose) {

    public AssetDerivationSource {
        DomainAssertions.required(derivedAssetId, "derivedAssetId");
        DomainAssertions.required(sourceAssetId, "sourceAssetId");
        DomainAssertions.nonNegative(sourceOrdinal, "sourceOrdinal");
        DomainAssertions.notBlank(purpose, "purpose");
        if (derivedAssetId.equals(sourceAssetId)) {
            throw new IllegalArgumentException("an asset cannot derive from itself");
        }
    }
}
