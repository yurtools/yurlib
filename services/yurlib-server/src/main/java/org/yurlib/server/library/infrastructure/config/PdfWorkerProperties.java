package org.yurlib.server.library.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("yurlib.pdf-worker")
public record PdfWorkerProperties(Path stagingDirectory, String token, Duration leaseDuration) {

    public PdfWorkerProperties {
        stagingDirectory = stagingDirectory == null ? Path.of(".local/staging/pdf") : stagingDirectory;
        token = token == null ? "" : token;
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(2) : leaseDuration;
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("The PDF worker lease duration must be positive.");
        }
    }
}
