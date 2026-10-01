package org.yurlib.server.library.application;

import java.nio.file.Path;
import java.time.Instant;
import org.yurlib.server.library.domain.LibraryRoot;

public interface ScanDiscovery {

    DiscoveryResult discover(LibraryRoot root, Listener listener);

    interface Listener {

        default boolean cancellationRequested() {
            return false;
        }

        void heartbeat();

        void discovered(Candidate candidate);

        void failed(String normalizedRelativePath, String code, String safeDiagnostic);
    }

    record Candidate(
            String normalizedRelativePath, Path containedFile, long byteSize, Instant modifiedAt, String fileKey) {}

    record DiscoveryResult(boolean coverageComplete) {}
}
