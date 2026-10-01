package org.yurlib.server.library.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("yurlib.cover-worker")
public record CoverWorkerProperties(Path stagingDirectory, Duration leaseDuration) {

    public CoverWorkerProperties {
        stagingDirectory = stagingDirectory == null ? Path.of(".local/staging/covers") : stagingDirectory;
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(2) : leaseDuration;
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("The cover worker lease duration must be positive.");
        }
    }
}
