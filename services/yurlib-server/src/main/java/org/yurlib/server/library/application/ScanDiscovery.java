package org.yurlib.server.library.application;

import org.yurlib.server.library.domain.LibraryRoot;

public interface ScanDiscovery {

    DiscoveryResult discover(LibraryRoot root, Listener listener);

    interface Listener {

        void heartbeat();

        void discovered(String normalizedRelativePath);

        void failed(String normalizedRelativePath, String code, String safeDiagnostic);
    }

    record DiscoveryResult(boolean coverageComplete) {}
}
