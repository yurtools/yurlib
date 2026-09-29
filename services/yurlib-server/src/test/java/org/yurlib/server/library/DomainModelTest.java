package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.domain.ScanJob;

class DomainModelTest {

    @Test
    void rejectsAnInvalidIdentityDigest() {
        assertThatThrownBy(() -> new LibraryRoot(
                        UUID.randomUUID(),
                        "Main library",
                        "library-main",
                        "books",
                        "not-a-digest",
                        LibraryRoot.Mode.READ_ONLY,
                        LibraryRoot.Availability.UNKNOWN,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void rejectsScanCountersThatExceedDiscovery() {
        assertThatThrownBy(() -> new ScanJob(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ScanJob.State.RUNNING,
                        "correlation-id",
                        "extractor-v1",
                        Instant.now(),
                        Instant.now(),
                        Instant.now(),
                        null,
                        1,
                        1,
                        1,
                        0,
                        false,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void rejectsNonNormalizedRelativePaths() {
        assertThatThrownBy(() -> new FileOutcome(
                        UUID.randomUUID(),
                        "books/../outside.fb2",
                        FileOutcome.State.DISCOVERED,
                        null,
                        null,
                        1,
                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized relative path");
    }
}
