package org.yurlib.server.library.infrastructure.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.yurlib.server.library.application.MetadataExtractionResult;

@SuppressWarnings("try")
class MetadataResourceBudgetTest {

    @Test
    void reportsTheSpecificConsumedResourceAndConfiguredBound() {
        var budget = new MetadataResourceBudget(limits(10, 4, Duration.ofSeconds(1)));

        assertThatThrownBy(() -> budget.recordRead(11))
                .isInstanceOfSatisfying(MetadataParsingException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(MetadataExtractionResult.ErrorCode.PARSE_LIMIT_EXCEEDED);
                    assertThat(failure.getMessage())
                            .isEqualTo("Metadata extraction exceeded the bytes-read limit (10 bytes).");
                });
    }

    @Test
    void enforcesSourceDepthSelectedValueAndOpenFileBoundsIndependently() throws Exception {
        var sourceBudget = new MetadataResourceBudget(limits(100, 4, Duration.ofSeconds(1)));
        assertThatThrownBy(() -> sourceBudget.checkSourceSize(101))
                .hasMessageContaining("source-size")
                .hasMessageContaining("100 bytes");

        var depthBudget = new MetadataResourceBudget(limits(100, 4, Duration.ofSeconds(1)));
        assertThatThrownBy(() -> depthBudget.checkDepth(5)).hasMessageContaining("structural-depth");

        var valueBudget = new MetadataResourceBudget(limits(100, 4, Duration.ofSeconds(1)));
        assertThatThrownBy(() -> valueBudget.checkSelectedValueBytes(17)).hasMessageContaining("selected-value");

        var fileBudget = new MetadataResourceBudget(limits(100, 1, Duration.ofSeconds(1)));
        try (var ignored = fileBudget.openFile()) {
            assertThatThrownBy(fileBudget::openFile).hasMessageContaining("open-files");
        }
        try (var ignored = fileBudget.openFile()) {
            assertThat(fileBudget.usage().peakOpenFiles()).isEqualTo(1);
        }
    }

    @Test
    void enforcesMonotonicElapsedDeadline() {
        var clock = new AtomicLong();
        var budget = new MetadataResourceBudget(limits(100, 4, Duration.ofNanos(10)), clock::get);
        clock.set(11);

        assertThatThrownBy(budget::checkpoint).hasMessageContaining("elapsed-time");
    }

    private static MetadataResourceLimits limits(long byteLimit, int openFiles, Duration elapsed) {
        return new MetadataResourceLimits(byteLimit, byteLimit, 32, 2, 32, 16, 4, openFiles, elapsed);
    }
}
