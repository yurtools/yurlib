package org.yurlib.server.library.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.yurlib.server.library.application.ConfigureLibraryRootService;
import org.yurlib.server.library.application.DefaultScanJobService;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.LibraryRootUseCases;
import org.yurlib.server.library.application.MetadataExtractor;
import org.yurlib.server.library.application.MissingLocationReconciler;
import org.yurlib.server.library.application.RootLocationVerifier;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.application.ScanJobStore;
import org.yurlib.server.library.application.ScanJobUseCases;
import org.yurlib.server.library.application.ScanJobWorker;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemRootVerifier;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemScanDiscovery;
import org.yurlib.server.library.infrastructure.filesystem.MountAliasRegistry;
import org.yurlib.server.library.infrastructure.metadata.BoundedMetadataExtractor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LibraryStorageProperties.class)
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
    MissingLocationReconciler missingLocationReconciler() {
        return (rootId, scanJobId) -> {
            // Issue #24 supplies catalog-location reconciliation behind this boundary.
        };
    }

    @Bean
    MetadataExtractor metadataExtractor() {
        return new BoundedMetadataExtractor();
    }

    @Bean
    ScanJobUseCases scanJobUseCases(LibraryRootStore roots, ScanJobStore jobs, Clock clock) {
        return new DefaultScanJobService(roots, jobs, clock);
    }

    @Bean
    ScanJobWorker scanJobWorker(
            LibraryRootStore roots,
            ScanJobStore jobs,
            ScanDiscovery discovery,
            MissingLocationReconciler reconciler,
            Clock clock,
            @Value("${yurlib.library.scan.lease-timeout:PT1M}") Duration leaseTimeout) {
        return new ScanJobWorker(roots, jobs, discovery, reconciler, clock, leaseTimeout);
    }
}
