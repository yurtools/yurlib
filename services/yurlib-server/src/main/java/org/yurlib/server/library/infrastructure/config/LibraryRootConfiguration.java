package org.yurlib.server.library.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.yurlib.server.library.application.AssetContentStore;
import org.yurlib.server.library.application.AssetFileOpener;
import org.yurlib.server.library.application.CatalogCandidateReconciler;
import org.yurlib.server.library.application.CatalogStore;
import org.yurlib.server.library.application.ConfigureLibraryRootService;
import org.yurlib.server.library.application.CoverContentStore;
import org.yurlib.server.library.application.CoverContentUseCases;
import org.yurlib.server.library.application.CoverQueue;
import org.yurlib.server.library.application.DefaultCatalogCandidateReconciler;
import org.yurlib.server.library.application.DefaultCoverContentService;
import org.yurlib.server.library.application.DefaultOriginalAssetContentService;
import org.yurlib.server.library.application.DefaultScanJobService;
import org.yurlib.server.library.application.IngestionResourceGovernor;
import org.yurlib.server.library.application.IngestionTaskStore;
import org.yurlib.server.library.application.IngestionTaskWorker;
import org.yurlib.server.library.application.LibraryRootAccess;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.LibraryRootUseCases;
import org.yurlib.server.library.application.MetadataExtractor;
import org.yurlib.server.library.application.MissingLocationReconciler;
import org.yurlib.server.library.application.OriginalAssetContentUseCases;
import org.yurlib.server.library.application.PdfMetadataQueue;
import org.yurlib.server.library.application.RootLocationVerifier;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.application.ScanJobTelemetry;
import org.yurlib.server.library.application.ScanJobUseCases;
import org.yurlib.server.library.application.ScanJobWorker;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemAssetFileOpener;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemCoverFileOpener;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemRootVerifier;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemScanDiscovery;
import org.yurlib.server.library.infrastructure.filesystem.MountAliasRegistry;
import org.yurlib.server.library.infrastructure.metadata.BoundedMetadataExtractor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LibraryStorageProperties.class, PdfWorkerProperties.class, CoverWorkerProperties.class})
@EnableScheduling
public class LibraryRootConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    MountAliasRegistry mountAliasRegistry(LibraryStorageProperties properties) {
        return new MountAliasRegistry(properties.mounts());
    }

    @Bean
    FilesystemRootVerifier rootLocationVerifier(MountAliasRegistry registry) {
        return new FilesystemRootVerifier(registry);
    }

    @Bean
    LibraryRootUseCases libraryRootUseCases(LibraryRootStore store, RootLocationVerifier verifier) {
        return new ConfigureLibraryRootService(store, verifier);
    }

    @Bean
    ScanDiscovery scanDiscovery(FilesystemRootVerifier verifier) {
        return new FilesystemScanDiscovery(verifier);
    }

    @Bean
    MissingLocationReconciler missingLocationReconciler(CatalogStore catalog) {
        return catalog::markUnseenMissing;
    }

    @Bean
    MetadataExtractor metadataExtractor() {
        return new BoundedMetadataExtractor();
    }

    @Bean
    ScanJobUseCases scanJobUseCases(
            LibraryRootStore roots,
            ScanJobStore jobs,
            LibraryRootAccess access,
            MetadataExtractor extractor,
            IngestionTaskStore tasks,
            Clock clock) {
        return new DefaultScanJobService(roots, jobs, access, extractor, tasks, clock);
    }

    @Bean
    CatalogCandidateReconciler catalogCandidateReconciler(
            CatalogStore catalog,
            MetadataExtractor extractor,
            PdfMetadataQueue pdfQueue,
            CoverQueue coverQueue,
            Clock clock) {
        return new DefaultCatalogCandidateReconciler(catalog, extractor, pdfQueue, coverQueue, clock);
    }

    @Bean
    AssetFileOpener assetFileOpener(FilesystemRootVerifier verifier) {
        return new FilesystemAssetFileOpener(verifier);
    }

    @Bean
    OriginalAssetContentUseCases originalAssetContentUseCases(
            AssetContentStore assets, LibraryRootStore roots, AssetFileOpener opener) {
        return new DefaultOriginalAssetContentService(assets, roots, opener);
    }

    @Bean
    CoverContentUseCases coverContentUseCases(
            CoverContentStore covers, LibraryRootStore roots, FilesystemRootVerifier verifier) {
        return new DefaultCoverContentService(covers, roots, new FilesystemCoverFileOpener(verifier));
    }

    @Bean
    ScanJobWorker scanJobWorker(
            LibraryRootStore roots,
            ScanJobStore jobs,
            ScanDiscovery discovery,
            IngestionTaskStore tasks,
            MissingLocationReconciler reconciler,
            Clock clock,
            ScanJobTelemetry telemetry,
            @Value("${yurlib.library.scan.lease-timeout:PT1M}") Duration leaseTimeout,
            @Value("${yurlib.library.scan.queue-capacity:256}") int queueCapacity) {
        return new ScanJobWorker(
                roots, jobs, discovery, tasks, reconciler, clock, leaseTimeout, telemetry, queueCapacity);
    }

    @Bean(destroyMethod = "close")
    ExecutorService ingestionExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("yurlib-ingestion-", 0).factory());
    }

    @Bean
    IngestionResourceGovernor ingestionResourceGovernor(
            @Value("${yurlib.library.scan.memory-mib:512}") int memoryMib,
            @Value("${yurlib.library.scan.open-files:32}") int openFiles,
            @Value("${yurlib.library.scan.cpu-permits:2}") int cpuPermits,
            @Value("${yurlib.library.scan.database-permits:2}") int databasePermits) {
        return new IngestionResourceGovernor(memoryMib, openFiles, cpuPermits, databasePermits);
    }

    @Bean
    IngestionTaskWorker ingestionTaskWorker(
            LibraryRootStore roots,
            ScanJobStore jobs,
            IngestionTaskStore tasks,
            CatalogCandidateReconciler candidateReconciler,
            MissingLocationReconciler reconciler,
            FilesystemRootVerifier rootVerifier,
            IngestionResourceGovernor governor,
            ScanJobTelemetry telemetry,
            Clock clock,
            @Value("${yurlib.library.scan.task-lease-duration:PT1M}") Duration leaseDuration,
            @Value("${yurlib.library.scan.maximum-attempts:3}") int maximumAttempts,
            @Value("${yurlib.library.scan.metadata-workers:2}") int metadataWorkers) {
        var fairRootShare = Math.max(1, metadataWorkers / 2);
        return new IngestionTaskWorker(
                roots,
                jobs,
                tasks,
                candidateReconciler,
                reconciler,
                rootVerifier,
                governor,
                telemetry,
                clock,
                leaseDuration,
                maximumAttempts,
                fairRootShare);
    }
}
