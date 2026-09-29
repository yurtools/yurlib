package org.yurlib.server.library.domain;

import java.util.UUID;
import java.util.regex.Pattern;

public record Asset(
        UUID id,
        UUID editionId,
        Format format,
        long byteSize,
        Derivation derivation,
        String contentHash,
        String extractionVersion) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public Asset {
        DomainAssertions.required(id, "id");
        DomainAssertions.required(editionId, "editionId");
        DomainAssertions.required(format, "format");
        DomainAssertions.nonNegative(byteSize, "byteSize");
        DomainAssertions.required(derivation, "derivation");
        DomainAssertions.notBlank(extractionVersion, "extractionVersion");
        if (derivation != Derivation.ORIGINAL) {
            throw new IllegalArgumentException("the first slice supports original assets only");
        }
        if (contentHash != null && !SHA_256.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 digest");
        }
    }

    public enum Format {
        EPUB,
        FB2,
        MOBI
    }

    public enum Derivation {
        ORIGINAL
    }
}
