package org.yurlib.server.library.application;

import java.util.UUID;

public interface LibraryAccessContext {

    Access current();

    record Access(UUID userId, boolean unrestricted) {
        public Access {
            if (!unrestricted && userId == null) {
                throw new IllegalArgumentException("A restricted library context requires a user.");
            }
        }
    }
}
