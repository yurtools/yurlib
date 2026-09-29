package org.yurlib.server.library.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record LibraryRoot(
        UUID id,
        String name,
        String mountAlias,
        String relativeBasePath,
        String expectedIdentityDigest,
        Mode mode,
        Availability availability,
        Instant lastSuccessfulScanAt) {

    private static final Pattern MOUNT_ALIAS = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public LibraryRoot {
        DomainAssertions.required(id, "id");
        DomainAssertions.notBlank(name, "name");
        DomainAssertions.notBlank(mountAlias, "mountAlias");
        DomainAssertions.normalizedRelativePath(relativeBasePath, "relativeBasePath", true);
        DomainAssertions.notBlank(expectedIdentityDigest, "expectedIdentityDigest");
        DomainAssertions.required(mode, "mode");
        DomainAssertions.required(availability, "availability");
        if (!MOUNT_ALIAS.matcher(mountAlias).matches()) {
            throw new IllegalArgumentException("mountAlias must be a normalized alias");
        }
        if (!SHA_256.matcher(expectedIdentityDigest).matches()) {
            throw new IllegalArgumentException("expectedIdentityDigest must be a lowercase SHA-256 digest");
        }
        if (mode != Mode.READ_ONLY) {
            throw new IllegalArgumentException("the first slice supports read-only roots only");
        }
    }

    public enum Mode {
        READ_ONLY
    }

    public enum Availability {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE,
        IDENTITY_MISMATCH
    }
}
