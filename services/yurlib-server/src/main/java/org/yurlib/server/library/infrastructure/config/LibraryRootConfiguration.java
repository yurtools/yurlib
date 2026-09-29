package org.yurlib.server.library.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yurlib.server.library.application.ConfigureLibraryRootService;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.LibraryRootUseCases;
import org.yurlib.server.library.application.RootLocationVerifier;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemRootVerifier;
import org.yurlib.server.library.infrastructure.filesystem.MountAliasRegistry;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LibraryStorageProperties.class)
public class LibraryRootConfiguration {

    @Bean
    MountAliasRegistry mountAliasRegistry(LibraryStorageProperties properties) {
        return new MountAliasRegistry(properties.mounts());
    }

    @Bean
    RootLocationVerifier rootLocationVerifier(MountAliasRegistry registry) {
        return new FilesystemRootVerifier(registry);
    }

    @Bean
    LibraryRootUseCases libraryRootUseCases(LibraryRootStore store, RootLocationVerifier verifier) {
        return new ConfigureLibraryRootService(store, verifier);
    }
}
