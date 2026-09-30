package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.testing.fixtures.FixtureCorpus;

@Testcontainers(disabledWithoutDocker = true)
class CatalogProvenanceMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void upgradesM1CatalogWithoutLosingRowsHashesOrSourceEvidence() throws Exception {
        var schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        flyway(schema, "3").migrate();
        var sourceHashes = FixtureCorpus.sourceHashes();
        var storedHash = sourceHashes.get("valid/minimal.fb2");
        var ids = insertM1Catalog(schema, storedHash);

        flyway(schema, "4").migrate();

        try (var connection = connection(schema);
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery(
                    "SELECT metadata_value FROM yurlib_metadata WHERE metadata_key = 'schema_version'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("4");
            }
            try (var result = statement.executeQuery("SELECT content_hash, format, derivation FROM asset")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("content_hash")).isEqualTo(storedHash);
                assertThat(result.getString("format")).isEqualTo("FB2");
                assertThat(result.getString("derivation")).isEqualTo("ORIGINAL");
            }
            try (var result = statement.executeQuery("SELECT provisional_title, content_kind FROM work")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("provisional_title")).isEqualTo("M1 title");
                assertThat(result.getString("content_kind")).isEqualTo("BOOK");
            }
            try (var result = statement.executeQuery("""
                    SELECT source_asset_id, source_root_id, parser_name, parser_version
                    FROM metadata_observation
                    WHERE id = '%s'
                    """.formatted(ids.observationId()))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("source_asset_id", UUID.class)).isEqualTo(ids.assetId());
                assertThat(result.getObject("source_root_id", UUID.class)).isEqualTo(ids.rootId());
                assertThat(result.getString("parser_name")).isEqualTo("m1-parser");
                assertThat(result.getString("parser_version")).isEqualTo("1");
            }
            assertThat(count(statement, "work")).isEqualTo(1);
            assertThat(count(statement, "edition")).isEqualTo(1);
            assertThat(count(statement, "asset")).isEqualTo(1);
            assertThat(count(statement, "asset_location")).isEqualTo(1);
            assertThat(count(statement, "metadata_observation")).isEqualTo(3);
            assertThat(count(statement, "metadata_observation_set")).isEqualTo(3);
            assertThat(count(statement, "contributor")).isEqualTo(1);
            assertThat(count(statement, "work_contributor")).isEqualTo(1);
            assertThat(count(statement, "edition_identifier")).isEqualTo(1);
        }
        FixtureCorpus.assertSourcesUnchanged(sourceHashes);
    }

    @Test
    void rollsBackAllV4ChangesWhenTheMigrationFails() throws Exception {
        var schema = "rollback_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        flyway(schema, "3").migrate();
        try (var connection = connection(schema);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata_observation_set (collision INTEGER)");
        }

        assertThatThrownBy(() -> flyway(schema, null).migrate()).isInstanceOf(FlywayException.class);

        try (var connection = connection(schema);
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'work'
                      AND column_name = 'content_kind'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            try (var result = statement.executeQuery(
                    "SELECT metadata_value FROM yurlib_metadata WHERE metadata_key = 'schema_version'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("3");
            }
        }
    }

    private static void createSchema(String schema) throws Exception {
        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private static Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static java.sql.Connection connection(String schema) throws Exception {
        var connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        connection.setSchema(schema);
        return connection;
    }

    private static M1Ids insertM1Catalog(String schema, String contentHash) throws Exception {
        var rootId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var workId = UUID.randomUUID();
        var editionId = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var observationId = UUID.randomUUID();
        try (var connection = connection(schema);
                var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO library_root (
                        id, name, mount_alias, relative_base_path, expected_identity_digest
                    ) VALUES (
                        '%s', 'M1 fixture root', 'fixtures', '', '%s'
                    )
                    """.formatted(rootId, "a".repeat(64)));
            statement.execute("""
                    INSERT INTO scan_job (
                        id, library_root_id, state, correlation_id, extraction_version
                    ) VALUES (
                        '%s', '%s', 'SUCCEEDED', 'm1-upgrade-fixture', 'm1-extractor'
                    )
                    """.formatted(jobId, rootId));
            statement.execute("""
                    INSERT INTO work (id, provisional_title, resolution_state)
                    VALUES ('%s', 'M1 title', 'PROVISIONAL')
                    """.formatted(workId));
            statement.execute("""
                    INSERT INTO edition (id, work_id, observed_language, identifiers, resolution_state)
                    VALUES ('%s', '%s', 'en', '{"isbn":"9780000000001"}', 'PROVISIONAL')
                    """.formatted(editionId, workId));
            statement.execute("""
                    INSERT INTO asset (
                        id, edition_id, format, byte_size, derivation, content_hash, extraction_version
                    ) VALUES (
                        '%s', '%s', 'FB2', 123, 'ORIGINAL', '%s', 'm1-extractor'
                    )
                    """.formatted(assetId, editionId, contentHash));
            statement.execute("""
                    INSERT INTO asset_location (
                        id, asset_id, library_root_id, normalized_relative_path, byte_size,
                        modified_at, availability, last_seen_scan_id
                    ) VALUES (
                        '%s', '%s', '%s', 'valid/minimal.fb2', 123,
                        CURRENT_TIMESTAMP, 'AVAILABLE', '%s'
                    )
                    """.formatted(UUID.randomUUID(), assetId, rootId, jobId));
            insertObservation(statement, observationId, workId, "WORK", "title", "M1 title");
            insertObservation(statement, UUID.randomUUID(), workId, "WORK", "contributor", "M1 Author");
            insertObservation(statement, UUID.randomUUID(), editionId, "EDITION", "identifier:isbn", "9780000000001");
        }
        return new M1Ids(rootId, assetId, observationId);
    }

    private static void insertObservation(
            java.sql.Statement statement,
            UUID observationId,
            UUID subjectId,
            String subjectType,
            String fieldName,
            String value)
            throws java.sql.SQLException {
        statement.execute("""
                INSERT INTO metadata_observation (
                    id, subject_id, subject_type, field_name, observed_value,
                    parser_name, parser_version
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', 'm1-parser', '1'
                )
                """.formatted(observationId, subjectId, subjectType, fieldName, value));
    }

    private static long count(java.sql.Statement statement, String table) throws java.sql.SQLException {
        try (var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private record M1Ids(UUID rootId, UUID assetId, UUID observationId) {}
}
