package org.yurlib.server.library;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.yurlib.server.library.application.IngestionResourceGovernor;

class IngestionResourceGovernorTest {

    @Test
    @SuppressWarnings("try")
    void reservesAndReleasesEveryAggregateResource() throws InterruptedException {
        var governor = new IngestionResourceGovernor(512, 32, 2, 2);

        try (var lease = governor.acquire(128)) {
            assertThat(governor.snapshot()).isEqualTo(new IngestionResourceGovernor.Snapshot(384, 28, 1, 1));
        }

        assertThat(governor.snapshot()).isEqualTo(new IngestionResourceGovernor.Snapshot(512, 32, 2, 2));
    }
}
