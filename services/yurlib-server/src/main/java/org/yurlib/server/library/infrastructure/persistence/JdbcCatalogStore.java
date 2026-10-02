package org.yurlib.server.library.infrastructure.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.CatalogLocationSnapshot;
import org.yurlib.server.library.application.CatalogQuery;
import org.yurlib.server.library.application.CatalogReconciliation;
import org.yurlib.server.library.application.CatalogStore;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.LibraryAccessContext;
import org.yurlib.server.library.domain.Asset;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcCatalogStore implements CatalogStore, CatalogQuery {

    private static final int MAXIMUM_QUERY_LENGTH = 200;
    private static final int MAXIMUM_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final LibraryAccessContext accessContext;
    private final JdbcMetadataProcessor metadataProcessor;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "The Spring-managed metadata processor is intentionally retained as a persistence collaborator.")
    public JdbcCatalogStore(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            LibraryAccessContext accessContext,
            JdbcMetadataProcessor metadataProcessor) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.rebuild().build();
        this.accessContext = accessContext;
        this.metadataProcessor = metadataProcessor;
    }

    @Override
    public Optional<CatalogLocationSnapshot> findLocation(UUID rootId, String normalizedRelativePath) {
        return jdbc.sql("""
                SELECT location.byte_size, location.modified_at, asset.extraction_version, asset.metadata_state
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
    public UUID reconcile(CatalogReconciliation reconciliation) {
        var existing = lockLocation(reconciliation.rootId(), reconciliation.normalizedRelativePath());
        if (existing.isPresent() && existing.get().sameBinaryFacts(reconciliation.metadata())) {
            updateExistingCatalog(existing.get(), reconciliation);
            return existing.get().assetId();
        } else {
            return insertCatalog(existing.orElse(null), reconciliation);
        }
    }

    @Override
    @Transactional
    public void markMetadataState(
            UUID rootId, String normalizedRelativePath, CatalogReconciliation.MetadataState metadataState) {
        jdbc.sql("""
                UPDATE asset
                SET metadata_state = :metadataState
                FROM asset_location location
                WHERE location.asset_id = asset.id
                  AND location.library_root_id = :rootId
                  AND location.normalized_relative_path = :path
                """)
                .param("metadataState", metadataState.name())
                .param("rootId", rootId)
                .param("path", normalizedRelativePath)
                .update();
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
    public CatalogPage search(String query, Set<Asset.Format> formats, int page, int size) {
        validatePage(query, page, size);
        var access = accessContext.current();
        var pattern = searchPattern(query);
        var selectedFormats = formats.isEmpty() ? Set.of(Asset.Format.values()) : Set.copyOf(formats);
        var formatNames = selectedFormats.stream().map(Enum::name).toList();
        var total = countWorks(pattern, formatNames, access);
        if (total == 0) {
            return new CatalogPage(List.of(), page, size, 0);
        }

        var works = findWorks(pattern, formatNames, page, size, access);
        var workIds = works.stream().map(WorkRow::id).toList();
        var contributors = findContributors(workIds);
        var assets = findAssets(workIds, access);
        var covers = findAccessibleCovers(workIds, access);
        var items = works.stream()
                .map(work -> new WorkSummary(
                        work.id(),
                        work.title(),
                        work.provisional(),
                        contributors.getOrDefault(work.id(), List.of()).stream()
                                .map(ContributorSummary::displayName)
                                .toList(),
                        assets.getOrDefault(work.id(), List.of()),
                        covers.contains(work.id()),
                        contributors.getOrDefault(work.id(), List.of())))
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
                SET extraction_version = :extractionVersion,
                    metadata_state = :metadataState
                WHERE id = :assetId
                """)
                .param("extractionVersion", reconciliation.extractionVersion())
                .param("metadataState", reconciliation.metadataState().name())
                .param("assetId", existing.assetId())
                .update();
        updateLocation(existing.locationId(), existing.assetId(), reconciliation);
        appendObservations(existing.workId(), existing.editionId(), existing.assetId(), reconciliation);
    }

    private UUID insertCatalog(LocationRow existing, CatalogReconciliation reconciliation) {
        var workId = UUID.randomUUID();
        var editionId = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var metadata = reconciliation.metadata();
        jdbc.sql("""
                INSERT INTO work (
                    id, provisional_title, content_kind, resolution_state, created_at, updated_at
                ) VALUES (
                    :id, :title, 'BOOK', 'PROVISIONAL', :observedAt, :observedAt
                )
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
                    id, edition_id, format, byte_size, derivation, extraction_version, metadata_state, created_at
                ) VALUES (
                    :id, :editionId, :format, :byteSize, 'ORIGINAL', :extractionVersion, :metadataState, :observedAt
                )
                """)
                .param("id", assetId)
                .param("editionId", editionId)
                .param("format", metadata.format().name())
                .param("byteSize", metadata.byteSize())
                .param("extractionVersion", reconciliation.extractionVersion())
                .param("metadataState", reconciliation.metadataState().name())
                .param("observedAt", timestamp(reconciliation.observedAt()))
                .update();
        if (existing == null) {
            insertLocation(assetId, reconciliation);
        } else {
            updateLocation(existing.locationId(), assetId, reconciliation);
        }
        appendObservations(workId, editionId, assetId, reconciliation);
        return assetId;
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

    private void appendObservations(UUID workId, UUID editionId, UUID assetId, CatalogReconciliation reconciliation) {
        var observationSetId = insertObservationSet(assetId, reconciliation);
        var metadata = reconciliation.metadata();
        insertObservation(observationSetId, assetId, workId, "WORK", "title", metadata.title(), 0, reconciliation);
        for (var index = 0; index < metadata.contributors().size(); index++) {
            insertObservation(
                    observationSetId,
                    assetId,
                    workId,
                    "WORK",
                    "contributor",
                    metadata.contributors().get(index),
                    index,
                    reconciliation);
        }
        insertObservation(
                observationSetId, assetId, editionId, "EDITION", "language", metadata.language(), 0, reconciliation);
        metadata.identifiers().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> insertObservation(
                        observationSetId,
                        assetId,
                        editionId,
                        "EDITION",
                        "identifier:" + entry.getKey(),
                        entry.getValue(),
                        0,
                        reconciliation));
        metadata.additionalObservations().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    for (var index = 0; index < entry.getValue().size(); index++) {
                        insertObservation(
                                observationSetId,
                                assetId,
                                assetId,
                                "ASSET",
                                entry.getKey(),
                                entry.getValue().get(index),
                                index,
                                reconciliation);
                    }
                });
        insertObservation(
                observationSetId,
                assetId,
                assetId,
                "ASSET",
                "format",
                metadata.format().name(),
                0,
                reconciliation);
        metadataProcessor.process(observationSetId, reconciliation.observedAt());
    }

    private UUID insertObservationSet(UUID assetId, CatalogReconciliation reconciliation) {
        var observationSetId = UUID.randomUUID();
        var metadata = reconciliation.metadata();
        jdbc.sql("""
                INSERT INTO metadata_observation_set (
                    id, source_asset_id, source_root_id, parser_name, parser_version,
                    extraction_version, observed_at, created_at
                ) VALUES (
                    :id, :assetId, :rootId, :parserName, :parserVersion,
                    :extractionVersion, :observedAt, :observedAt
                )
                """)
                .param("id", observationSetId)
                .param("assetId", assetId)
                .param("rootId", reconciliation.rootId())
                .param("parserName", metadata.parserName())
                .param("parserVersion", metadata.parserVersion())
                .param("extractionVersion", reconciliation.extractionVersion())
                .param("observedAt", timestamp(reconciliation.observedAt()))
                .update();
        return observationSetId;
    }

    private void insertObservation(
            UUID observationSetId,
            UUID sourceAssetId,
            UUID subjectId,
            String subjectType,
            String fieldName,
            String value,
            int valueOrdinal,
            CatalogReconciliation reconciliation) {
        if (value == null || value.isBlank()) {
            return;
        }
        var metadata = reconciliation.metadata();
        jdbc.sql("""
                INSERT INTO metadata_observation (
                    id, subject_id, subject_type, field_name, observed_value,
                    source, parser_name, parser_version, observed_at,
                    observation_set_id, source_asset_id, source_root_id, value_ordinal
                ) VALUES (
                    :id, :subjectId, :subjectType, :fieldName, :value,
                    'FILE', :parserName, :parserVersion, :observedAt,
                    :observationSetId, :sourceAssetId, :sourceRootId, :valueOrdinal
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
                .param("observationSetId", observationSetId)
                .param("sourceAssetId", sourceAssetId)
                .param("sourceRootId", reconciliation.rootId())
                .param("valueOrdinal", valueOrdinal)
                .update();
    }

    private long countWorks(String pattern, List<String> formats, LibraryAccessContext.Access access) {
        return jdbc.sql("""
                SELECT count(*)
                FROM work
                WHERE work.merged_into_id IS NULL
                AND EXISTS (
                    SELECT 1
                    FROM edition visible_edition
                    JOIN asset visible_asset ON visible_asset.edition_id = visible_edition.id
                    JOIN asset_location visible_location ON visible_location.asset_id = visible_asset.id
                    WHERE visible_edition.work_id = work.id
                      AND visible_location.availability = 'AVAILABLE'
                      AND visible_asset.format IN (:formats)
                      AND (:unrestricted OR NOT EXISTS (
                          SELECT 1 FROM user_root_deny denied
                          WHERE denied.user_id = :userId
                            AND denied.library_root_id = visible_location.library_root_id
                      ))
                      AND (visible_asset.derivation = 'ORIGINAL' OR :unrestricted OR NOT EXISTS (
                          WITH RECURSIVE source_lineage(asset_id) AS (
                              SELECT source_asset_id
                              FROM asset_derivation_source
                              WHERE derived_asset_id = visible_asset.id
                              UNION
                              SELECT source.source_asset_id
                              FROM asset_derivation_source source
                              JOIN source_lineage ON source.derived_asset_id = source_lineage.asset_id
                          )
                          SELECT 1
                          FROM source_lineage lineage
                          JOIN asset_location source_location ON source_location.asset_id = lineage.asset_id
                          JOIN user_root_deny denied_source
                            ON denied_source.library_root_id = source_location.library_root_id
                           AND denied_source.user_id = :userId
                      ))
                )
                AND (lower(COALESCE((
                       SELECT display.display_value
                       FROM catalog_metadata_display display
                       WHERE display.subject_type = 'WORK'
                         AND display.subject_id = work.id
                         AND display.field_name = 'title'
                   ), work.provisional_title)) LIKE :pattern ESCAPE '\\'
                   OR EXISTS (
                       SELECT 1
                       FROM edition path_edition
                       JOIN asset path_asset ON path_asset.edition_id = path_edition.id
                       JOIN asset_location path_location ON path_location.asset_id = path_asset.id
                       WHERE path_edition.work_id = work.id
                         AND lower(path_location.normalized_relative_path) LIKE :pattern ESCAPE '\\'
                         AND (:unrestricted OR NOT EXISTS (
                             SELECT 1 FROM user_root_deny denied
                             WHERE denied.user_id = :userId
                               AND denied.library_root_id = path_location.library_root_id
                         ))
                   )
                   OR EXISTS (
                       SELECT 1 FROM metadata_observation observation
                       WHERE observation.subject_type = 'WORK'
                         AND observation.subject_id = work.id
                         AND observation.field_name = 'contributor'
                         AND lower(observation.observed_value) LIKE :pattern ESCAPE '\\'
                         AND (:unrestricted OR NOT EXISTS (
                             SELECT 1 FROM user_root_deny denied
                             WHERE denied.user_id = :userId
                               AND denied.library_root_id = observation.source_root_id
                         ))
                   )
                   OR EXISTS (
                       SELECT 1
                       FROM edition identifier_edition,
                            jsonb_each_text(identifier_edition.identifiers) identifier
                       WHERE identifier_edition.work_id = work.id
                         AND (lower(identifier.key) LIKE :pattern ESCAPE '\\'
                             OR lower(identifier.value) LIKE :pattern ESCAPE '\\')
                   )
                   OR EXISTS (
                       SELECT 1
                       FROM work_tag assigned
                       JOIN catalog_tag tag ON tag.id = assigned.tag_id
                       WHERE assigned.work_id = work.id
                         AND lower(tag.name) LIKE :pattern ESCAPE '\\'
                   ))
                """)
                .param("pattern", pattern)
                .param("formats", formats)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(Long.class)
                .single();
    }

    private List<WorkRow> findWorks(
            String pattern, List<String> formats, int page, int size, LibraryAccessContext.Access access) {
        return jdbc.sql("""
                SELECT work.id,
                       COALESCE((
                           SELECT display.display_value
                           FROM catalog_metadata_display display
                           WHERE display.subject_type = 'WORK'
                             AND display.subject_id = work.id
                             AND display.field_name = 'title'
                       ), work.provisional_title) AS title,
                       NOT EXISTS (
                           SELECT 1
                           FROM catalog_metadata_display display
                           WHERE display.subject_type = 'WORK'
                             AND display.subject_id = work.id
                             AND display.field_name = 'title'
                             AND display.metadata_source IN ('CURATED', 'RESOLVED')
                       ) AS provisional
                FROM work
                WHERE work.merged_into_id IS NULL
                AND EXISTS (
                    SELECT 1
                    FROM edition visible_edition
                    JOIN asset visible_asset ON visible_asset.edition_id = visible_edition.id
                    JOIN asset_location visible_location ON visible_location.asset_id = visible_asset.id
                    WHERE visible_edition.work_id = work.id
                      AND visible_location.availability = 'AVAILABLE'
                      AND visible_asset.format IN (:formats)
                      AND (:unrestricted OR NOT EXISTS (
                          SELECT 1 FROM user_root_deny denied
                          WHERE denied.user_id = :userId
                            AND denied.library_root_id = visible_location.library_root_id
                      ))
                      AND (visible_asset.derivation = 'ORIGINAL' OR :unrestricted OR NOT EXISTS (
                          WITH RECURSIVE source_lineage(asset_id) AS (
                              SELECT source_asset_id
                              FROM asset_derivation_source
                              WHERE derived_asset_id = visible_asset.id
                              UNION
                              SELECT source.source_asset_id
                              FROM asset_derivation_source source
                              JOIN source_lineage ON source.derived_asset_id = source_lineage.asset_id
                          )
                          SELECT 1
                          FROM source_lineage lineage
                          JOIN asset_location source_location ON source_location.asset_id = lineage.asset_id
                          JOIN user_root_deny denied_source
                            ON denied_source.library_root_id = source_location.library_root_id
                           AND denied_source.user_id = :userId
                      ))
                )
                AND (lower(COALESCE((
                       SELECT display.display_value
                       FROM catalog_metadata_display display
                       WHERE display.subject_type = 'WORK'
                         AND display.subject_id = work.id
                         AND display.field_name = 'title'
                   ), work.provisional_title)) LIKE :pattern ESCAPE '\\'
                   OR EXISTS (
                       SELECT 1
                       FROM edition path_edition
                       JOIN asset path_asset ON path_asset.edition_id = path_edition.id
                       JOIN asset_location path_location ON path_location.asset_id = path_asset.id
                       WHERE path_edition.work_id = work.id
                         AND lower(path_location.normalized_relative_path) LIKE :pattern ESCAPE '\\'
                         AND (:unrestricted OR NOT EXISTS (
                             SELECT 1 FROM user_root_deny denied
                             WHERE denied.user_id = :userId
                               AND denied.library_root_id = path_location.library_root_id
                         ))
                   )
                   OR EXISTS (
                       SELECT 1 FROM metadata_observation observation
                       WHERE observation.subject_type = 'WORK'
                         AND observation.subject_id = work.id
                         AND observation.field_name = 'contributor'
                         AND lower(observation.observed_value) LIKE :pattern ESCAPE '\\'
                         AND (:unrestricted OR NOT EXISTS (
                             SELECT 1 FROM user_root_deny denied
                             WHERE denied.user_id = :userId
                               AND denied.library_root_id = observation.source_root_id
                         ))
                   )
                   OR EXISTS (
                       SELECT 1
                       FROM edition identifier_edition,
                            jsonb_each_text(identifier_edition.identifiers) identifier
                       WHERE identifier_edition.work_id = work.id
                         AND (lower(identifier.key) LIKE :pattern ESCAPE '\\'
                             OR lower(identifier.value) LIKE :pattern ESCAPE '\\')
                   )
                   OR EXISTS (
                       SELECT 1
                       FROM work_tag assigned
                       JOIN catalog_tag tag ON tag.id = assigned.tag_id
                       WHERE assigned.work_id = work.id
                         AND lower(tag.name) LIKE :pattern ESCAPE '\\'
                   ))
                ORDER BY title, work.id
                LIMIT :size OFFSET :offset
                """)
                .param("pattern", pattern)
                .param("formats", formats)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .param("size", size)
                .param("offset", Math.multiplyExact(page, size))
                .query(WorkRow.class)
                .list();
    }

    private Map<UUID, List<ContributorSummary>> findContributors(List<UUID> workIds) {
        var result = new LinkedHashMap<UUID, List<ContributorSummary>>();
        jdbc.sql("""
                SELECT linked.work_id, contributor.id, contributor.display_name
                FROM work_contributor linked
                JOIN contributor ON contributor.id = linked.contributor_id
                WHERE linked.work_id IN (:workIds)
                ORDER BY linked.work_id, linked.role, linked.ordinal, contributor.display_name
                """)
                .param("workIds", workIds)
                .query((row, rowNumber) -> new ContributorRow(
                        row.getObject("work_id", UUID.class),
                        row.getObject("id", UUID.class),
                        row.getString("display_name")))
                .list()
                .forEach(row -> result.computeIfAbsent(row.workId(), ignored -> new ArrayList<>())
                        .add(new ContributorSummary(row.id(), row.displayName())));
        return result;
    }

    private Map<UUID, List<AssetSummary>> findAssets(List<UUID> workIds, LibraryAccessContext.Access access) {
        var result = new LinkedHashMap<UUID, List<AssetSummary>>();
        jdbc.sql("""
                SELECT edition.work_id, asset.id, asset.format, asset.byte_size, asset.derivation,
                       asset.metadata_state,
                       bool_or(location.availability = 'AVAILABLE') AS available
                FROM asset
                JOIN edition ON edition.id = asset.edition_id
                JOIN asset_location location ON location.asset_id = asset.id
                WHERE edition.work_id IN (:workIds)
                  AND (:unrestricted OR NOT EXISTS (
                      SELECT 1 FROM user_root_deny denied
                      WHERE denied.user_id = :userId
                        AND denied.library_root_id = location.library_root_id
                  ))
                  AND (asset.derivation = 'ORIGINAL' OR :unrestricted OR NOT EXISTS (
                      WITH RECURSIVE source_lineage(asset_id) AS (
                          SELECT source_asset_id
                          FROM asset_derivation_source
                          WHERE derived_asset_id = asset.id
                          UNION
                          SELECT source.source_asset_id
                          FROM asset_derivation_source source
                          JOIN source_lineage ON source.derived_asset_id = source_lineage.asset_id
                      )
                      SELECT 1
                      FROM source_lineage lineage
                      JOIN asset_location source_location ON source_location.asset_id = lineage.asset_id
                      JOIN user_root_deny denied_source
                        ON denied_source.library_root_id = source_location.library_root_id
                       AND denied_source.user_id = :userId
                  ))
                GROUP BY edition.work_id, asset.id, asset.format, asset.byte_size, asset.derivation,
                         asset.metadata_state
                ORDER BY edition.work_id, asset.id
                """)
                .param("workIds", workIds)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query((row, rowNumber) -> new AssetRow(
                        row.getObject("work_id", UUID.class),
                        row.getObject("id", UUID.class),
                        Asset.Format.valueOf(row.getString("format")),
                        row.getLong("byte_size"),
                        row.getBoolean("available"),
                        "ORIGINAL".equals(row.getString("derivation")),
                        CatalogReconciliation.MetadataState.valueOf(row.getString("metadata_state"))))
                .list()
                .forEach(row -> result.computeIfAbsent(row.workId(), ignored -> new ArrayList<>())
                        .add(new AssetSummary(
                                row.id(),
                                row.format(),
                                row.size(),
                                row.available() ? Availability.AVAILABLE : Availability.UNAVAILABLE,
                                row.original(),
                                row.metadataState())));
        return result;
    }

    private Set<UUID> findAccessibleCovers(List<UUID> workIds, LibraryAccessContext.Access access) {
        return Set.copyOf(jdbc.sql("""
                WITH RECURSIVE candidate AS (
                    SELECT derivative.id, derivative.work_id, derivative.source_asset_id,
                           derivative.managed_root_id
                    FROM cover_derivative derivative
                    WHERE derivative.work_id IN (:workIds)
                ), lineage(candidate_id, asset_id) AS (
                    SELECT id, source_asset_id FROM candidate
                    UNION
                    SELECT lineage.candidate_id, source.source_asset_id
                    FROM lineage
                    JOIN asset_derivation_source source ON source.derived_asset_id = lineage.asset_id
                )
                SELECT DISTINCT candidate.work_id
                FROM candidate
                WHERE :unrestricted
                   OR (NOT EXISTS (
                           SELECT 1 FROM user_root_deny denied
                           WHERE denied.user_id = :userId
                             AND denied.library_root_id = candidate.managed_root_id)
                       AND NOT EXISTS (
                           SELECT 1
                           FROM lineage
                           JOIN asset_location location ON location.asset_id = lineage.asset_id
                           JOIN user_root_deny denied
                             ON denied.library_root_id = location.library_root_id
                            AND denied.user_id = :userId
                           WHERE lineage.candidate_id = candidate.id))
                """)
                .param("workIds", workIds)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(UUID.class)
                .list());
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

    private record ContributorRow(UUID workId, UUID id, String displayName) {}

    private record AssetRow(
            UUID workId,
            UUID id,
            Asset.Format format,
            long size,
            boolean available,
            boolean original,
            CatalogReconciliation.MetadataState metadataState) {}
}
