package org.yurlib.server.library.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.application.ScanJobWorker;

@Component
@ConditionalOnProperty(name = "yurlib.library.scan.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledScanJobRunner {

    private final ScanJobWorker worker;

    public ScheduledScanJobRunner(ScanJobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${yurlib.library.scan.poll-delay:1s}")
    public void runOneJob() {
        worker.runNext();
    }
}
