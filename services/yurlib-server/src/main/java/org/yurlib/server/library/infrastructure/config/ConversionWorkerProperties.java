package org.yurlib.server.library.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("yurlib.conversion-worker")
public record ConversionWorkerProperties(Path stagingDirectory, Duration leaseDuration) {

    public ConversionWorkerProperties {
        stagingDirectory = stagingDirectory == null ? Path.of(".local/staging/conversions") : stagingDirectory;
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(2) : leaseDuration;
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("The conversion worker lease duration must be positive.");
        }
    }
}
