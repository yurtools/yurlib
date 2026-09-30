package org.yurlib.server.library.infrastructure.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.application.LibraryAccessContext;
import org.yurlib.server.library.application.LibraryRootAccess;

@Component
final class JdbcLibraryRootAccess implements LibraryRootAccess {

    private final JdbcClient jdbc;
    private final LibraryAccessContext accessContext;

    JdbcLibraryRootAccess(JdbcClient jdbc, LibraryAccessContext accessContext) {
        this.jdbc = jdbc;
        this.accessContext = accessContext;
    }

    @Override
    public boolean isAllowed(UUID rootId) {
        var access = accessContext.current();
        return access.unrestricted()
                || jdbc.sql("""
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM user_root_deny
                    WHERE user_id = :userId
                      AND library_root_id = :rootId
                )
                """)
                        .param("userId", access.userId())
                        .param("rootId", rootId)
                        .query(Boolean.class)
                        .single();
    }
}
