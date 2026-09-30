package org.yurlib.server.library.application;

import java.time.Duration;
import org.yurlib.server.library.domain.ScanJob;

public interface ScanJobTelemetry {

    void started(ScanJob job);

    void fileFailed(String code);

    void completed(ScanJob job, Duration duration);

    void failed(ScanJob job, String code, Duration duration);
}
