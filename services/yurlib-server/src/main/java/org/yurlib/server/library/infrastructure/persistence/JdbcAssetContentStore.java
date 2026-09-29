package org.yurlib.server.library.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.yurlib.server.library.application.AssetContentLocation;
import org.yurlib.server.library.application.AssetContentStore;

@Component
public class JdbcAssetContentStore implements AssetContentStore {

    private final JdbcClient jdbc;

    public JdbcAssetContentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AssetContentLocation> findByAssetId(UUID assetId) {
        return jdbc.sql("""
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
                ORDER BY CASE WHEN location.availability = 'AVAILABLE' THEN 0 ELSE 1 END,
                         location.normalized_relative_path
                LIMIT 1
                """)
                .param("assetId", assetId)
                .query(AssetContentLocation.class)
                .optional();
    }
}
