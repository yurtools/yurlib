package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.library.application.LibraryAccessContext;
import org.yurlib.server.library.application.PersonalLibraryFailure;
import org.yurlib.server.library.infrastructure.persistence.JdbcPersonalLibraryStore;
import org.yurlib.server.security.JdbcUserAccountStore;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "yurlib.library.scan.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class PersonalLibraryIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("4938289c-0344-4570-b710-15bdacef82c9");
    private static final UUID READER_ID = UUID.fromString("404852e1-953f-41c9-a840-5a70093d9c43");
    private static final UUID OTHER_READER_ID = UUID.fromString("865d3e34-6c13-444a-b542-b0b4309aaf74");
    private static final UUID ROOT_ID = UUID.fromString("e12d56a9-a18d-4f76-bf1b-78f1669db0af");
    private static final UUID FIRST_WORK_ID = UUID.fromString("467115fd-5bb4-45ca-8e34-3b9f92227030");
    private static final UUID FIRST_EDITION_ID = UUID.fromString("d9a69394-1385-41c8-8588-1b0c0a21e95a");
    private static final UUID SECOND_WORK_ID = UUID.fromString("6ec2e738-d33c-494f-951b-cac2ba56b791");
    private static final UUID SECOND_EDITION_ID = UUID.fromString("92218423-3150-480e-a9bb-b2b8761a9f36");
    private static final UUID CONTRIBUTOR_ID = UUID.fromString("43724057-3235-4259-b1fe-825053f37738");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private AtomicReference<LibraryAccessContext.Access> access;
    private JdbcPersonalLibraryStore personalLibrary;

    @BeforeEach
    void setUp() {
        seedUser(OWNER_ID, "owner", true);
        seedUser(READER_ID, "reader", false);
        seedUser(OTHER_READER_ID, "other-reader", false);
        seedCatalog();
        access = new AtomicReference<>(new LibraryAccessContext.Access(READER_ID, false));
        personalLibrary = new JdbcPersonalLibraryStore(jdbc, access::get);
    }

    @Test
    void keepsPersonalStatePrivateVersionedAndHiddenWithoutDeletingMembership() {
        var favorite = personalLibrary.addFavorite(CONTRIBUTOR_ID);
        assertThat(personalLibrary.addFavorite(CONTRIBUTOR_ID)).isEqualTo(favorite);

        var read = personalLibrary.markRead(FIRST_WORK_ID, FIRST_EDITION_ID, Instant.parse("2026-10-02T12:00:00Z"), -1);
        assertThat(read.version()).isZero();
        assertThatThrownBy(() -> personalLibrary.markRead(
                        FIRST_WORK_ID, FIRST_EDITION_ID, Instant.parse("2026-10-02T13:00:00Z"), -1))
                .isInstanceOf(PersonalLibraryFailure.class)
                .extracting(failure -> ((PersonalLibraryFailure) failure).code())
                .isEqualTo(PersonalLibraryFailure.Code.PERSONAL_VERSION_CONFLICT);
        assertThatThrownBy(() -> personalLibrary.markRead(
                        FIRST_WORK_ID, SECOND_EDITION_ID, Instant.parse("2026-10-02T13:00:00Z"), 0))
                .isInstanceOf(PersonalLibraryFailure.class)
                .extracting(failure -> ((PersonalLibraryFailure) failure).code())
                .isEqualTo(PersonalLibraryFailure.Code.EDITION_NOT_IN_WORK);

        var collection = personalLibrary.createCollection("Reading order", true);
        collection = personalLibrary.addToCollection(collection.id(), FIRST_WORK_ID, collection.version());
        collection = personalLibrary.addToCollection(collection.id(), SECOND_WORK_ID, collection.version());
        assertThat(collection.works()).extracting(item -> item.position()).containsExactly(0, 1);
        var ownedCollection = collection;

        var snapshot = personalLibrary.snapshot();
        assertThat(snapshot.favoriteContributors()).hasSize(1);
        assertThat(snapshot.readStates()).hasSize(1);
        assertThat(snapshot.collections())
                .singleElement()
                .satisfies(item -> assertThat(item.works()).hasSize(2));

        access.set(new LibraryAccessContext.Access(OTHER_READER_ID, false));
        assertThat(personalLibrary.snapshot().favoriteContributors()).isEmpty();
        assertThat(personalLibrary.snapshot().readStates()).isEmpty();
        assertThat(personalLibrary.snapshot().collections()).isEmpty();
        assertThatThrownBy(() -> personalLibrary.deleteCollection(ownedCollection.id(), ownedCollection.version()))
                .isInstanceOf(PersonalLibraryFailure.class)
                .extracting(failure -> ((PersonalLibraryFailure) failure).code())
                .isEqualTo(PersonalLibraryFailure.Code.PERSONAL_ITEM_NOT_FOUND);

        access.set(new LibraryAccessContext.Access(READER_ID, false));
        jdbc.sql("INSERT INTO user_root_deny (user_id, library_root_id) VALUES (:userId, :rootId)")
                .param("userId", READER_ID)
                .param("rootId", ROOT_ID)
                .update();
        var hidden = personalLibrary.snapshot();
        assertThat(hidden.favoriteContributors()).isEmpty();
        assertThat(hidden.readStates()).isEmpty();
        assertThat(hidden.collections())
                .singleElement()
                .satisfies(item -> assertThat(item.works()).isEmpty());
        assertThat(jdbc.sql("SELECT count(*) FROM personal_collection_work WHERE collection_id = :id")
                        .param("id", ownedCollection.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        jdbc.sql("DELETE FROM user_root_deny WHERE user_id = :userId AND library_root_id = :rootId")
                .param("userId", READER_ID)
                .param("rootId", ROOT_ID)
                .update();
        assertThat(personalLibrary.snapshot().collections())
                .singleElement()
                .satisfies(item -> assertThat(item.works()).hasSize(2));
    }

    @Test
    void accountRemovalAnonymizesTheIdentityAndDeletesPersonalState() {
        personalLibrary.addFavorite(CONTRIBUTOR_ID);
        personalLibrary.markRead(FIRST_WORK_ID, null, Instant.parse("2026-10-02T12:00:00Z"), -1);
        personalLibrary.createCollection("Private", false);

        var users = new JdbcUserAccountStore(jdbc, objectMapper);
        users.removeUser(OWNER_ID, READER_ID);

        assertThat(users.findById(READER_ID)).isEmpty();
        assertThat(jdbc.sql("SELECT removed_at IS NOT NULL FROM user_account WHERE id = :id")
                        .param("id", READER_ID)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM user_contributor_favorite WHERE user_id = :id")
                        .param("id", READER_ID)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM user_work_read_state WHERE user_id = :id")
                        .param("id", READER_ID)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM personal_collection WHERE user_id = :id")
                        .param("id", READER_ID)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private void seedCatalog() {
        jdbc.sql("""
                INSERT INTO library_root (
                    id, name, mount_alias, relative_base_path, expected_identity_digest, mode, availability
                ) VALUES (
                    :id, 'Personal state root', 'personal-test', '', :digest,
                    'READ_ONLY_SOURCE', 'AVAILABLE'
                )
                """).param("id", ROOT_ID).param("digest", "a".repeat(64)).update();
        jdbc.sql("""
                INSERT INTO contributor (id, display_name, kind)
                VALUES (:id, 'Ursula K. Le Guin', 'PERSON')
                """).param("id", CONTRIBUTOR_ID).update();
        seedWork(FIRST_WORK_ID, FIRST_EDITION_ID, "The Dispossessed", CONTRIBUTOR_ID, 0);
        seedWork(SECOND_WORK_ID, SECOND_EDITION_ID, "The Left Hand of Darkness", null, 1);
    }

    private void seedWork(UUID workId, UUID editionId, String title, UUID contributorId, int ordinal) {
        var assetId = UUID.randomUUID();
        jdbc.sql("INSERT INTO work (id, provisional_title, content_kind) VALUES (:id, :title, 'BOOK')")
                .param("id", workId)
                .param("title", title)
                .update();
        jdbc.sql("INSERT INTO edition (id, work_id) VALUES (:id, :workId)")
                .param("id", editionId)
                .param("workId", workId)
                .update();
        jdbc.sql("""
                INSERT INTO asset (id, edition_id, format, byte_size, derivation, extraction_version)
                VALUES (:id, :editionId, 'EPUB', 10, 'ORIGINAL', 'personal-test')
                """).param("id", assetId).param("editionId", editionId).update();
        jdbc.sql("""
                INSERT INTO asset_location (
                    id, asset_id, library_root_id, normalized_relative_path,
                    byte_size, modified_at, availability
                ) VALUES (
                    :id, :assetId, :rootId, :path, 10, CURRENT_TIMESTAMP, 'AVAILABLE'
                )
                """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .param("rootId", ROOT_ID)
                .param("path", "work-" + ordinal + ".epub")
                .update();
        if (contributorId != null) {
            jdbc.sql("""
                    INSERT INTO work_contributor (work_id, contributor_id, role, ordinal)
                    VALUES (:workId, :contributorId, 'AUTHOR', 0)
                    """)
                    .param("workId", workId)
                    .param("contributorId", contributorId)
                    .update();
        }
    }

    private void seedUser(UUID id, String username, boolean owner) {
        jdbc.sql("""
                INSERT INTO user_account (
                    id, username, normalized_username, password_hash, owner, enabled
                ) VALUES (
                    :id, :username, :username, '{noop}test-password', :owner, TRUE
                )
                """)
                .param("id", id)
                .param("username", username)
                .param("owner", owner)
                .update();
    }
}
