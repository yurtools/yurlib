package org.yurlib.server.library.application;

import java.util.UUID;

public interface MissingLocationReconciler {

    void reconcileAfterCompleteScan(UUID rootId, UUID scanJobId);
}
