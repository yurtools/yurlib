package org.yurlib.server.library.application;

import java.util.List;
import java.util.UUID;
import org.yurlib.server.library.domain.FileOutcome;
import org.yurlib.server.library.domain.ScanJob;

public interface ScanJobUseCases {

    ScanJob queue(UUID rootId, String correlationId);

    ScanJobView get(UUID jobId);

    record ScanJobView(ScanJob job, List<FileOutcome> failures) {
        public ScanJobView {
            failures = List.copyOf(failures);
        }
    }
}
