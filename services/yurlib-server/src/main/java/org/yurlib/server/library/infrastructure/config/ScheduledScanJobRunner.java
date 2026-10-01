package org.yurlib.server.library.infrastructure.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.application.IngestionTaskWorker;
import org.yurlib.server.library.application.ScanJobWorker;

@Component
@ConditionalOnProperty(name = "yurlib.library.scan.worker-enabled", havingValue = "true", matchIfMissing = true)
public final class ScheduledScanJobRunner {

    private final ScanJobWorker worker;
    private final IngestionTaskWorker taskWorker;
    private final ExecutorService executor;
    private final AtomicInteger activeDiscovery = new AtomicInteger();
    private final AtomicInteger activeMetadata = new AtomicInteger();
    private final int discoveryWorkers;
    private final int metadataWorkers;

    public ScheduledScanJobRunner(
            ScanJobWorker worker,
            IngestionTaskWorker taskWorker,
            ExecutorService executor,
            @Value("${yurlib.library.scan.discovery-workers:1}") int discoveryWorkers,
            @Value("${yurlib.library.scan.metadata-workers:2}") int metadataWorkers) {
        if (discoveryWorkers != 1 || metadataWorkers <= 0 || metadataWorkers > 4) {
            throw new IllegalArgumentException(
                    "Yurlib requires one discovery worker and between one and four metadata workers.");
        }
        this.worker = worker;
        this.taskWorker = taskWorker;
        this.executor = executor;
        this.discoveryWorkers = discoveryWorkers;
        this.metadataWorkers = metadataWorkers;
    }

    @Scheduled(fixedDelayString = "${yurlib.library.scan.poll-delay:1s}")
    public void dispatchBoundedWorkers() {
        dispatch(activeDiscovery, discoveryWorkers, worker::runNext);
        dispatch(activeMetadata, metadataWorkers, taskWorker::runNext);
    }

    private void dispatch(AtomicInteger active, int maximum, Runnable operation) {
        while (reserve(active, maximum)) {
            executor.execute(() -> {
                try {
                    operation.run();
                } finally {
                    active.decrementAndGet();
                }
            });
        }
    }

    private static boolean reserve(AtomicInteger active, int maximum) {
        while (true) {
            var current = active.get();
            if (current >= maximum) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }
}
