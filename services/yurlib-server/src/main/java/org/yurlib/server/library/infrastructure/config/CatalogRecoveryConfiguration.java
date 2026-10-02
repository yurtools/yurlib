package org.yurlib.server.library.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yurlib.server.library.application.CatalogRecoveryFailureInjector;

@Configuration(proxyBeanMethods = false)
class CatalogRecoveryConfiguration {

    @Bean
    CatalogRecoveryFailureInjector catalogRecoveryFailureInjector() {
        return () -> {
            // Production recovery has no injected failure. Integration tests replace this seam.
        };
    }
}
