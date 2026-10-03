package org.yurlib.server.library.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.yurlib.server.library.domain.LibraryRoot;

@Entity
@Table(name = "library_root")
class LibraryRootEntity {

    @Id
    private UUID id;

    @Column(length = 100)
    private String name;

    @Column(name = "mount_alias", length = 63)
    private String mountAlias;

    @Column(name = "relative_base_path", length = 1024)
    private String relativeBasePath;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "expected_identity_digest", length = 64)
    private String expectedIdentityDigest;

    @Column(length = 20)
    private String mode;

    @Column(length = 30)
    private String availability;

    @Column(name = "last_successful_scan_at")
    private Instant lastSuccessfulScanAt;

    @Column(name = "default_for_covers")
    private boolean defaultForCovers;

    @Column(name = "default_for_conversions")
    private boolean defaultForConversions;

    protected LibraryRootEntity() {
        // Required by JPA.
    }

    LibraryRootEntity(LibraryRoot root) {
        id = root.id();
        name = root.name();
        mountAlias = root.mountAlias();
        relativeBasePath = root.relativeBasePath();
        expectedIdentityDigest = root.expectedIdentityDigest();
        mode = root.mode().name();
        availability = root.availability().name();
        lastSuccessfulScanAt = root.lastSuccessfulScanAt();
        defaultForCovers = root.defaultForCovers();
        defaultForConversions = root.defaultForConversions();
    }

    LibraryRoot toDomain() {
        return new LibraryRoot(
                id,
                name,
                mountAlias,
                relativeBasePath,
                expectedIdentityDigest,
                LibraryRoot.Mode.valueOf(mode),
                LibraryRoot.Availability.valueOf(availability),
                lastSuccessfulScanAt,
                defaultForCovers,
                defaultForConversions);
    }
}
