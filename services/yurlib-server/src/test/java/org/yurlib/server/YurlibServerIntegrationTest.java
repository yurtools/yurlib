package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.domain.LibraryRoot;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class YurlibServerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LibraryRootStore libraryRootStore;

    @Test
    void appliesFlywayMigrations() {
        var value = JdbcClient.create(dataSource).sql("""
                SELECT metadata_value
                FROM yurlib_metadata
                WHERE metadata_key = 'schema_version'
                """).query(String.class).single();

        assertThat(value).isEqualTo("3");
    }

    @Test
    void createsTheLocalLibraryTablesFromAnEmptyDatabase() {
        var tables = JdbcClient.create(dataSource).sql("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """).query(String.class).list();

        assertThat(tables).containsAll(Set.of(
                "library_root",
                "scan_job",
                "file_outcome",
                "work",
                "edition",
                "asset",
                "asset_location",
                "metadata_observation"));
    }

    @Test
    void enforcesOneActiveScanPerLibraryRoot() {
        var client = JdbcClient.create(dataSource);
        var rootId = UUID.randomUUID();
        client.sql("""
                INSERT INTO library_root (
                    id, name, mount_alias, relative_base_path, expected_identity_digest
                ) VALUES (
                    :id, 'Main library', 'library-main', '', :digest
                )
                """)
                .param("id", rootId)
                .param("digest", "0".repeat(64))
                .update();
        insertQueuedScan(client, rootId, UUID.randomUUID());

        assertThatThrownBy(() -> insertQueuedScan(client, rootId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonNormalizedStoredPaths() {
        var client = JdbcClient.create(dataSource);

        assertThatThrownBy(() -> client.sql("""
                INSERT INTO library_root (
                    id, name, mount_alias, relative_base_path, expected_identity_digest
                ) VALUES (
                    :id, 'Escaping library', 'library-escape', :path, :digest
                )
                """)
                .param("id", UUID.randomUUID())
                .param("path", "books\\..\\outside")
                .param("digest", "0".repeat(64))
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsOnlyTheIdentityDigestForAConfiguredRoot() {
        var identityDigest = "a".repeat(64);
        var saved = libraryRootStore.save(new LibraryRoot(
                UUID.randomUUID(),
                "Main library",
                "main",
                "books",
                identityDigest,
                LibraryRoot.Mode.READ_ONLY,
                LibraryRoot.Availability.AVAILABLE,
                null));

        assertThat(libraryRootStore.findAll()).containsExactly(saved);
        var storedDigest = JdbcClient.create(dataSource).sql("""
                SELECT expected_identity_digest
                FROM library_root
                WHERE id = :id
                """)
                .param("id", saved.id())
                .query(String.class)
                .single();
        assertThat(storedDigest).isEqualTo(identityDigest);
    }

    private static void insertQueuedScan(JdbcClient client, UUID rootId, UUID jobId) {
        client.sql("""
                INSERT INTO scan_job (
                    id, library_root_id, state, correlation_id, extraction_version
                ) VALUES (
                    :id, :rootId, 'QUEUED', :correlationId, 'extractor-v1'
                )
                """)
                .param("id", jobId)
                .param("rootId", rootId)
                .param("correlationId", jobId.toString())
                .update();
    }
}
