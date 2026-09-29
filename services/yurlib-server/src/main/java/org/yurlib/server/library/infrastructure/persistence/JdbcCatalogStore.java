package org.yurlib.server.library.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.CatalogLocationSnapshot;
import org.yurlib.server.library.application.CatalogQuery;
import org.yurlib.server.library.application.CatalogReconciliation;
import org.yurlib.server.library.application.CatalogStore;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.domain.Asset;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcCatalogStore implements CatalogStore, CatalogQuery {

    private static final int MAXIMUM_QUERY_LENGTH = 200;
    private static final int MAXIMUM_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCatalogStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.rebuild().build();
    }

    @Override
    public Optional<CatalogLocationSnapshot> findLocation(UUID rootId, String normalizedRelativePath) {
        return jdbc.sql("""
                SELECT location.byte_size, location.modified_at, asset.extraction_version
                FROM asset_location location
                JOIN asset ON asset.id = location.asset_id
                WHERE location.library_root_id = :rootId
                  AND location.normalized_relative_path = :path
                """)
                .param("rootId", rootId)
                .param("path", normalizedRelativePath)
                .query(CatalogLocationSnapshot.class)
                .optional();
    }

    @Override
    @Transactional
    public void markSeen(UUID rootId, UUID scanJobId, String normalizedRelativePath) {
        jdbc.sql("""
                UPDATE asset_location
                SET availability = 'AVAILABLE',
                    last_seen_scan_id = :scanJobId,
                    updated_at = CURRENT_TIMESTAMP
                WHERE library_root_id = :rootId
                  AND normalized_relative_path = :path
                """)
                .param("rootId", rootId)
                .param("scanJobId", scanJobId)
                .param("path", normalizedRelativePath)
                .update();
    }

    @Override
    @Transactional
    public void reconcile(CatalogReconciliation reconciliation) {
        var existing = lockLocation(reconciliation.rootId(), reconciliation.normalizedRelativePath());
        if (existing.isPresent() && existing.get().sameBinaryFacts(reconciliation.metadata())) {
            updateExistingCatalog(existing.get(), reconciliation);
        } else {
            insertCatalog(existing.orElse(null), reconciliation);
        }
    }

    @Override
    @Transactional
    public void markUnseenMissing(UUID rootId, UUID scanJobId) {
        jdbc.sql("""
                UPDATE asset_location
                SET availability = 'MISSING',
                    updated_at = CURRENT_TIMESTAMP
                WHERE library_root_id = :rootId
                  AND (last_seen_scan_id IS NULL OR last_seen_scan_id <> :scanJobId)
                """).param("rootId", rootId).param("scanJobId", scanJobId).update();
    }

    @Override
    public CatalogPage search(String query, int page, int size) {
        validatePage(query, page, size);
        var pattern = searchPattern(query);
        var total = countWorks(pattern);
        if (total == 0) {
            return new CatalogPage(List.of(), page, size, 0);
        }

        var works = findWorks(pattern, page, size);
        var workIds = works.stream().map(WorkRow::id).toList();
        var contributors = findContributors(workIds);
        var assets = findAssets(workIds);
        var items = works.stream()
                .map(work -> new WorkSummary(
                        work.id(),
                        work.title(),
                        work.provisional(),
                        contributors.getOrDefault(work.id(), List.of()),
                        assets.getOrDefault(work.id(), List.of())))
                .toList();
        return new CatalogPage(items, page, size, total);
    }

    private Optional<LocationRow> lockLocation(UUID rootId, String path) {
        return jdbc.sql("""
                SELECT location.id AS location_id, location.asset_id,
                       asset.edition_id, edition.work_id,
                       location.byte_size, location.modified_at
                FROM asset_location location
                JOIN asset ON asset.id = location.asset_id
                JOIN edition ON edition.id = asset.edition_id
                WHERE location.library_root_id = :rootId
                  AND location.normalized_relative_path = :path
                FOR UPDATE OF location
                """)
                .param("rootId", rootId)
                .param("path", path)
                .query(LocationRow.class)
                .optional();
    }

    private void updateExistingCatalog(LocationRow existing, CatalogReconciliation reconciliation) {
        var metadata = reconciliation.metadata();
        jdbc.sql("""
                UPDATE work
                SET provisional_title = :title, updated_at = :updatedAt
                WHERE id = :workId
                """)
                .param("title", title(metadata, reconciliation.normalizedRelativePath()))
                .param("updatedAt", timestamp(reconciliation.observedAt()))
                .param("workId", existing.workId())
                .update();
        jdbc.sql("""
                UPDATE edition
                SET observed_language = :language,
                    identifiers = CAST(:identifiers AS jsonb),
                    updated_at = :updatedAt
                WHERE id = :editionId
                """)
                .param("language", metadata.language())
                .param("identifiers", json(metadata.identifiers()))
                .param("updatedAt", timestamp(reconciliation.observedAt()))
                .param("editionId", existing.editionId())
                .update();
        jdbc.sql("""
                UPDATE asset
                SET extraction_version = :extractionVersion
                WHERE id = :assetId
                """)
                .param("extractionVersion", reconciliation.extractionVersion())
                .param("assetId", existing.assetId())
                .update();
        updateLocation(existing.locationId(), existing.assetId(), reconciliation);
        replaceObservations(existing.workId(), existing.editionId(), existing.assetId(), reconciliation);
    }

    private void insertCatalog(LocationRow existing, CatalogReconciliation reconciliation) {
        var workId = UUID.randomUUID();
        var editionId = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var metadata = reconciliation.metadata();
        jdbc.sql("""
                INSERT INTO work (id, provisional_title, resolution_state, created_at, updated_at)
                VALUES (:id, :title, 'PROVISIONAL', :observedAt, :observedAt)
                """)
                .param("id", workId)
                .param("title", title(metadata, reconciliation.normalizedRelativePath()))
                .param("observedAt", timestamp(reconciliation.observedAt()))
                .update();
        jdbc.sql("""
                INSERT INTO edition (
                    id, work_id, observed_language, identifiers, resolution_state, created_at, updated_at
                ) VALUES (
                    :id, :workId, :language, CAST(:identifiers AS jsonb),
                    'PROVISIONAL', :observedAt, :observedAt
                )
                """)
                .param("id", editionId)
                .param("workId", workId)
                .param("language", metadata.language())
                .param("identifiers", json(metadata.identifiers()))
                .param("observedAt", timestamp(reconciliation.observedAt()))
                .update();
        jdbc.sql("""
                INSERT INTO asset (
                    id, edition_id, format, byte_size, derivation, extraction_version, created_at
                ) VALUES (
                    :id, :editionId, :format, :byteSize, 'ORIGINAL', :extractionVersion, :observedAt
                )
                """)
                .param("id", assetId)
                .param("editionId", editionId)
                .param("format", metadata.format().name())
                .param("byteSize", metadata.byteSize())
                .param("extractionVersion", reconciliation.extractionVersion())
                .param("observedAt", timestamp(reconciliation.observedAt()))
                .update();
        if (existing == null) {
            insertLocation(assetId, reconciliation);
        } else {
            updateLocation(existing.locationId(), assetId, reconciliation);
        }
        insertObservations(workId, editionId, assetId, reconciliation);
    }

    private void insertLocation(UUID assetId, CatalogReconciliation reconciliation) {
        jdbc.sql("""
                INSERT INTO asset_location (
                    id, asset_id, library_root_id, normalized_relative_path,
                    byte_size, modified_at, file_key, availability, last_seen_scan_id,
                    created_at, updated_at
                ) VALUES (
                    :id, :assetId, :rootId, :path, :byteSize, :modifiedAt, :fileKey,
                    'AVAILABLE', :scanJobId, :observedAt, :observedAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .param("rootId", reconciliation.rootId())
                .param("path", reconciliation.normalizedRelativePath())
                .param("byteSize", reconciliation.metadata().byteSize())
                .param("modifiedAt", timestamp(reconciliation.metadata().modifiedAt()))
                .param("fileKey", reconciliation.fileKey())
                .param("scanJobId", reconciliation.scanJobId())
                .param("observedAt", timestamp(reconciliation.observedAt()))
                .update();
    }

    private void updateLocation(UUID locationId, UUID assetId, CatalogReconciliation reconciliation) {
        jdbc.sql("""
                UPDATE asset_location
                SET asset_id = :assetId,
                    byte_size = :byteSize,
                    modified_at = :modifiedAt,
                    file_key = :fileKey,
                    availability = 'AVAILABLE',
                    last_seen_scan_id = :scanJobId,
                    updated_at = :observedAt
                WHERE id = :locationId
                """)
                .param("assetId", assetId)
                .param("byteSize", reconciliation.metadata().byteSize())
                .param("modifiedAt", timestamp(reconciliation.metadata().modifiedAt()))
                .param("fileKey", reconciliation.fileKey())
                .param("scanJobId", reconciliation.scanJobId())
                .param("observedAt", timestamp(reconciliation.observedAt()))
                .param("locationId", locationId)
                .update();
    }

    private void replaceObservations(UUID workId, UUID editionId, UUID assetId, CatalogReconciliation reconciliation) {
        jdbc.sql("""
                DELETE FROM metadata_observation
                WHERE (subject_type = 'WORK' AND subject_id = :workId)
                   OR (subject_type = 'EDITION' AND subject_id = :editionId)
                   OR (subject_type = 'ASSET' AND subject_id = :assetId)
                """)
                .param("workId", workId)
                .param("editionId", editionId)
                .param("assetId", assetId)
                .update();
        insertObservations(workId, editionId, assetId, reconciliation);
    }

    private void insertObservations(UUID workId, UUID editionId, UUID assetId, CatalogReconciliation reconciliation) {
        var metadata = reconciliation.metadata();
        insertObservation(workId, "WORK", "title", metadata.title(), reconciliation);
        for (var contributor : metadata.contributors()) {
            insertObservation(workId, "WORK", "contributor", contributor, reconciliation);
        }
        insertObservation(editionId, "EDITION", "language", metadata.language(), reconciliation);
        metadata.identifiers()
                .forEach((type, value) ->
                        insertObservation(editionId, "EDITION", "identifier:" + type, value, reconciliation));
        insertObservation(assetId, "ASSET", "format", metadata.format().name(), reconciliation);
    }

    private void insertObservation(
            UUID subjectId, String subjectType, String fieldName, String value, CatalogReconciliation reconciliation) {
        if (value == null || value.isBlank()) {
            return;
        }
        var metadata = reconciliation.metadata();
        jdbc.sql("""
                INSERT INTO metadata_observation (
                    id, subject_id, subject_type, field_name, observed_value,
                    source, parser_name, parser_version, observed_at
                ) VALUES (
                    :id, :subjectId, :subjectType, :fieldName, :value,
                    'FILE', :parserName, :parserVersion, :observedAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("subjectId", subjectId)
                .param("subjectType", subjectType)
                .param("fieldName", fieldName)
                .param("value", value)
                .param("parserName", metadata.parserName())
                .param("parserVersion", metadata.parserVersion())
                .param("observedAt", timestamp(reconciliation.observedAt()))
                .update();
    }

    private long countWorks(String pattern) {
        return jdbc.sql("""
                SELECT count(DISTINCT work.id)
                FROM work
                JOIN edition ON edition.work_id = work.id
                JOIN asset ON asset.edition_id = edition.id
                JOIN asset_location location ON location.asset_id = asset.id
                WHERE lower(work.provisional_title) LIKE :pattern ESCAPE '\\'
                   OR lower(location.normalized_relative_path) LIKE :pattern ESCAPE '\\'
                   OR EXISTS (
                       SELECT 1 FROM metadata_observation observation
                       WHERE observation.subject_type = 'WORK'
                         AND observation.subject_id = work.id
                         AND observation.field_name = 'contributor'
                         AND lower(observation.observed_value) LIKE :pattern ESCAPE '\\'
                   )
                   OR EXISTS (
                       SELECT 1 FROM jsonb_each_text(edition.identifiers) identifier
                       WHERE lower(identifier.key) LIKE :pattern ESCAPE '\\'
                          OR lower(identifier.value) LIKE :pattern ESCAPE '\\'
                   )
                """).param("pattern", pattern).query(Long.class).single();
    }

    private List<WorkRow> findWorks(String pattern, int page, int size) {
        return jdbc.sql("""
                SELECT DISTINCT work.id, work.provisional_title AS title,
                       work.resolution_state = 'PROVISIONAL' AS provisional
                FROM work
                JOIN edition ON edition.work_id = work.id
                JOIN asset ON asset.edition_id = edition.id
                JOIN asset_location location ON location.asset_id = asset.id
                WHERE lower(work.provisional_title) LIKE :pattern ESCAPE '\\'
                   OR lower(location.normalized_relative_path) LIKE :pattern ESCAPE '\\'
                   OR EXISTS (
                       SELECT 1 FROM metadata_observation observation
                       WHERE observation.subject_type = 'WORK'
                         AND observation.subject_id = work.id
                         AND observation.field_name = 'contributor'
                         AND lower(observation.observed_value) LIKE :pattern ESCAPE '\\'
                   )
                   OR EXISTS (
                       SELECT 1 FROM jsonb_each_text(edition.identifiers) identifier
                       WHERE lower(identifier.key) LIKE :pattern ESCAPE '\\'
                          OR lower(identifier.value) LIKE :pattern ESCAPE '\\'
                   )
                ORDER BY title, work.id
                LIMIT :size OFFSET :offset
                """)
                .param("pattern", pattern)
                .param("size", size)
                .param("offset", Math.multiplyExact(page, size))
                .query(WorkRow.class)
                .list();
    }

    private Map<UUID, List<String>> findContributors(List<UUID> workIds) {
        var result = new LinkedHashMap<UUID, List<String>>();
        jdbc.sql("""
                SELECT subject_id AS work_id, observed_value
                FROM metadata_observation
                WHERE subject_type = 'WORK'
                  AND field_name = 'contributor'
                  AND subject_id IN (:workIds)
                ORDER BY subject_id, observed_value
                """)
                .param("workIds", workIds)
                .query((row, rowNumber) ->
                        new ContributorRow(row.getObject("work_id", UUID.class), row.getString("observed_value")))
                .list()
                .forEach(row -> result.computeIfAbsent(row.workId(), ignored -> new ArrayList<>())
                        .add(row.value()));
        return result;
    }

    private Map<UUID, List<AssetSummary>> findAssets(List<UUID> workIds) {
        var result = new LinkedHashMap<UUID, List<AssetSummary>>();
        jdbc.sql("""
                SELECT edition.work_id, asset.id, asset.format, asset.byte_size,
                       bool_or(location.availability = 'AVAILABLE') AS available
                FROM asset
                JOIN edition ON edition.id = asset.edition_id
                JOIN asset_location location ON location.asset_id = asset.id
                WHERE edition.work_id IN (:workIds)
                GROUP BY edition.work_id, asset.id, asset.format, asset.byte_size
                ORDER BY edition.work_id, asset.id
                """)
                .param("workIds", workIds)
                .query((row, rowNumber) -> new AssetRow(
                        row.getObject("work_id", UUID.class),
                        row.getObject("id", UUID.class),
                        Asset.Format.valueOf(row.getString("format")),
                        row.getLong("byte_size"),
                        row.getBoolean("available")))
                .list()
                .forEach(row -> result.computeIfAbsent(row.workId(), ignored -> new ArrayList<>())
                        .add(new AssetSummary(
                                row.id(),
                                row.format(),
                                row.size(),
                                row.available() ? Availability.AVAILABLE : Availability.UNAVAILABLE,
                                true)));
        return result;
    }

    private String json(Map<String, String> identifiers) {
        return objectMapper.writeValueAsString(identifiers);
    }

    private static String title(ExtractedBookMetadata metadata, String path) {
        if (metadata.title() != null && !metadata.title().isBlank()) {
            return metadata.title().strip();
        }
        var separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static void validatePage(String query, int page, int size) {
        if (query != null && query.length() > MAXIMUM_QUERY_LENGTH) {
            throw new IllegalArgumentException("query must not exceed 200 characters");
        }
        if (page < 0 || size < 1 || size > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
    }

    private static String searchPattern(String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        var escaped = query.strip()
                .toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record LocationRow(
            UUID locationId, UUID assetId, UUID editionId, UUID workId, long byteSize, Instant modifiedAt) {

        private boolean sameBinaryFacts(ExtractedBookMetadata metadata) {
            return byteSize == metadata.byteSize() && modifiedAt.equals(metadata.modifiedAt());
        }
    }

    private record WorkRow(UUID id, String title, boolean provisional) {}

    private record ContributorRow(UUID workId, String value) {}

    private record AssetRow(UUID workId, UUID id, Asset.Format format, long size, boolean available) {}
}
