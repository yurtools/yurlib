package org.yurlib.server.library.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.application.AssetContentLocation;
import org.yurlib.server.library.application.AssetContentStore;
import org.yurlib.server.library.application.LibraryAccessContext;

@Component
public class JdbcAssetContentStore implements AssetContentStore {

    private final JdbcClient jdbc;
    private final LibraryAccessContext accessContext;

    public JdbcAssetContentStore(JdbcClient jdbc, LibraryAccessContext accessContext) {
        this.jdbc = jdbc;
        this.accessContext = accessContext;
    }

    @Override
    public Optional<AssetContentLocation> findByAssetId(UUID assetId) {
        var access = accessContext.current();
        return jdbc.sql("""
                WITH RECURSIVE source_lineage(asset_id) AS (
                    SELECT source_asset_id
                    FROM asset_derivation_source
                    WHERE derived_asset_id = :assetId
                    UNION
                    SELECT lineage.source_asset_id
                    FROM asset_derivation_source lineage
                    JOIN source_lineage ancestor
                      ON lineage.derived_asset_id = ancestor.asset_id
                )
                SELECT asset.id AS asset_id,
                       location.library_root_id AS root_id,
                       location.normalized_relative_path,
                       asset.format,
                       location.byte_size,
                       location.modified_at,
                       location.file_key,
                       location.availability
                FROM asset
                JOIN asset_location location ON location.asset_id = asset.id
                WHERE asset.id = :assetId
                  AND (:unrestricted OR NOT EXISTS (
                      SELECT 1
                      FROM user_root_deny denied
                      WHERE denied.user_id = :userId
                        AND denied.library_root_id = location.library_root_id
                  ))
                  AND (:unrestricted OR NOT EXISTS (
                      SELECT 1
                      FROM source_lineage lineage
                      JOIN asset_location source_location
                        ON source_location.asset_id = lineage.asset_id
                      JOIN user_root_deny denied_source
                        ON denied_source.library_root_id = source_location.library_root_id
                       AND denied_source.user_id = :userId
                  ))
                ORDER BY CASE WHEN location.availability = 'AVAILABLE' THEN 0 ELSE 1 END,
                         location.normalized_relative_path
                LIMIT 1
                """)
                .param("assetId", assetId)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(AssetContentLocation.class)
                .optional();
    }
}
