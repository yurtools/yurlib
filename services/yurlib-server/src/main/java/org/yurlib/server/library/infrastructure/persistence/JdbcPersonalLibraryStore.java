package org.yurlib.server.library.infrastructure.persistence;

import static org.yurlib.server.library.application.PersonalLibraryFailure.Code.COLLECTION_NAME_EXISTS;
import static org.yurlib.server.library.application.PersonalLibraryFailure.Code.EDITION_NOT_IN_WORK;
import static org.yurlib.server.library.application.PersonalLibraryFailure.Code.PERSONAL_ITEM_NOT_FOUND;
import static org.yurlib.server.library.application.PersonalLibraryFailure.Code.PERSONAL_VERSION_CONFLICT;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.LibraryAccessContext;
import org.yurlib.server.library.application.PersonalLibraryFailure;
import org.yurlib.server.library.application.PersonalLibraryUseCases;

@Component
public class JdbcPersonalLibraryStore implements PersonalLibraryUseCases {

    private static final UUID LOOPBACK_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcClient jdbc;
    private final LibraryAccessContext accessContext;

    public JdbcPersonalLibraryStore(JdbcClient jdbc, LibraryAccessContext accessContext) {
        this.jdbc = jdbc;
        this.accessContext = accessContext;
    }

    @Override
    @Transactional
    public Snapshot snapshot() {
        var identity = identity();
        var favorites = jdbc.sql("""
                SELECT favorite.contributor_id, contributor.display_name, favorite.created_at
                FROM user_contributor_favorite favorite
                JOIN contributor ON contributor.id = favorite.contributor_id
                WHERE favorite.user_id = :userId
                  AND EXISTS (
                      SELECT 1
                      FROM work_contributor linked
                      WHERE linked.contributor_id = favorite.contributor_id
                        AND yurlib_user_can_access_work(:userId, linked.work_id, :unrestricted)
                  )
                ORDER BY lower(contributor.display_name), contributor.id
                """)
                .param("userId", identity.userId())
                .param("unrestricted", identity.unrestricted())
                .query((row, rowNumber) -> new FavoriteContributor(
                        row.getObject("contributor_id", UUID.class),
                        row.getString("display_name"),
                        row.getTimestamp("created_at").toInstant()))
                .list();
        var readStates = jdbc.sql("""
                SELECT state.work_id, state.completed_edition_id, state.completed_at, state.version
                FROM user_work_read_state state
                WHERE state.user_id = :userId
                  AND yurlib_user_can_access_work(:userId, state.work_id, :unrestricted)
                ORDER BY state.completed_at DESC, state.work_id
                """)
                .param("userId", identity.userId())
                .param("unrestricted", identity.unrestricted())
                .query((row, rowNumber) -> new ReadState(
                        row.getObject("work_id", UUID.class),
                        row.getObject("completed_edition_id", UUID.class),
                        row.getTimestamp("completed_at").toInstant(),
                        row.getLong("version")))
                .list();
        var collections = jdbc.sql("""
                SELECT id, name, ordered, version
                FROM personal_collection
                WHERE user_id = :userId
                ORDER BY normalized_name, id
                """).param("userId", identity.userId()).query(CollectionRow.class).list().stream()
                .map(row -> collection(row, identity))
                .toList();
        return new Snapshot(favorites, readStates, collections);
    }

    @Override
    @Transactional
    public FavoriteContributor addFavorite(UUID contributorId) {
        var identity = identity();
        requireContributorVisible(contributorId, identity);
        jdbc.sql("""
                INSERT INTO user_contributor_favorite (user_id, contributor_id)
                VALUES (:userId, :contributorId)
                ON CONFLICT (user_id, contributor_id) DO NOTHING
                """)
                .param("userId", identity.userId())
                .param("contributorId", contributorId)
                .update();
        return jdbc.sql("""
                SELECT favorite.contributor_id, contributor.display_name, favorite.created_at
                FROM user_contributor_favorite favorite
                JOIN contributor ON contributor.id = favorite.contributor_id
                WHERE favorite.user_id = :userId AND favorite.contributor_id = :contributorId
                """)
                .param("userId", identity.userId())
                .param("contributorId", contributorId)
                .query((row, rowNumber) -> new FavoriteContributor(
                        row.getObject("contributor_id", UUID.class),
                        row.getString("display_name"),
                        row.getTimestamp("created_at").toInstant()))
                .single();
    }

    @Override
    @Transactional
    public void removeFavorite(UUID contributorId) {
        var identity = identity();
        requireContributorVisible(contributorId, identity);
        jdbc.sql("""
                DELETE FROM user_contributor_favorite
                WHERE user_id = :userId AND contributor_id = :contributorId
                """)
                .param("userId", identity.userId())
                .param("contributorId", contributorId)
                .update();
    }

    @Override
    @Transactional
    public ReadState markRead(UUID workId, UUID completedEditionId, Instant completedAt, long expectedVersion) {
        var identity = identity();
        requireWorkVisible(workId, identity);
        requireEditionInWork(workId, completedEditionId);
        if (expectedVersion < 0) {
            var inserted = jdbc.sql("""
                    INSERT INTO user_work_read_state (
                        user_id, work_id, completed_edition_id, completed_at
                    ) VALUES (:userId, :workId, :editionId, :completedAt)
                    ON CONFLICT (user_id, work_id) DO NOTHING
                    """)
                    .param("userId", identity.userId())
                    .param("workId", workId)
                    .param("editionId", completedEditionId)
                    .param("completedAt", Timestamp.from(completedAt))
                    .update();
            if (inserted == 0) {
                throw conflict();
            }
        } else {
            var updated = jdbc.sql("""
                    UPDATE user_work_read_state
                    SET completed_edition_id = :editionId,
                        completed_at = :completedAt,
                        version = version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = :userId AND work_id = :workId AND version = :expectedVersion
                    """)
                    .param("editionId", completedEditionId)
                    .param("completedAt", Timestamp.from(completedAt))
                    .param("userId", identity.userId())
                    .param("workId", workId)
                    .param("expectedVersion", expectedVersion)
                    .update();
            if (updated == 0) {
                throw conflict();
            }
        }
        return readState(identity.userId(), workId);
    }

    @Override
    @Transactional
    public void markUnread(UUID workId, long expectedVersion) {
        var identity = identity();
        requireWorkVisible(workId, identity);
        var deleted = jdbc.sql("""
                DELETE FROM user_work_read_state
                WHERE user_id = :userId AND work_id = :workId AND version = :expectedVersion
                """)
                .param("userId", identity.userId())
                .param("workId", workId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (deleted == 0) {
            throw conflict();
        }
    }

    @Override
    @Transactional
    public PersonalCollection createCollection(String name, boolean ordered) {
        var identity = identity();
        var id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO personal_collection (id, user_id, name, normalized_name, ordered)
                    VALUES (:id, :userId, :name, :normalizedName, :ordered)
                    """)
                    .param("id", id)
                    .param("userId", identity.userId())
                    .param("name", displayName(name))
                    .param("normalizedName", normalizedName(name))
                    .param("ordered", ordered)
                    .update();
        } catch (DataIntegrityViolationException failure) {
            throw collectionNameExists(failure);
        }
        return requireCollection(id, identity);
    }

    @Override
    @Transactional
    public PersonalCollection updateCollection(UUID collectionId, String name, boolean ordered, long expectedVersion) {
        var identity = identity();
        var current = requireCollectionRow(collectionId, identity);
        if (current.version() != expectedVersion) {
            throw conflict();
        }
        try {
            var updated = jdbc.sql("""
                    UPDATE personal_collection
                    SET name = :name,
                        normalized_name = :normalizedName,
                        ordered = :ordered,
                        version = version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id AND user_id = :userId AND version = :expectedVersion
                    """)
                    .param("name", displayName(name))
                    .param("normalizedName", normalizedName(name))
                    .param("ordered", ordered)
                    .param("id", collectionId)
                    .param("userId", identity.userId())
                    .param("expectedVersion", expectedVersion)
                    .update();
            if (updated == 0) {
                throw conflict();
            }
        } catch (DataIntegrityViolationException failure) {
            throw collectionNameExists(failure);
        }
        if (ordered && !current.ordered()) {
            jdbc.sql("""
                    UPDATE personal_collection_work member
                    SET position = positions.position
                    FROM (
                        SELECT work_id, row_number() OVER (ORDER BY added_at, work_id) - 1 AS position
                        FROM personal_collection_work
                        WHERE collection_id = :collectionId
                    ) positions
                    WHERE member.collection_id = :collectionId AND member.work_id = positions.work_id
                    """).param("collectionId", collectionId).update();
        } else if (!ordered && current.ordered()) {
            jdbc.sql("UPDATE personal_collection_work SET position = NULL WHERE collection_id = :collectionId")
                    .param("collectionId", collectionId)
                    .update();
        }
        return requireCollection(collectionId, identity);
    }

    @Override
    @Transactional
    public void deleteCollection(UUID collectionId, long expectedVersion) {
        var identity = identity();
        var deleted = jdbc.sql("""
                DELETE FROM personal_collection
                WHERE id = :id AND user_id = :userId AND version = :expectedVersion
                """)
                .param("id", collectionId)
                .param("userId", identity.userId())
                .param("expectedVersion", expectedVersion)
                .update();
        if (deleted == 0) {
            distinguishMissingCollection(collectionId, identity);
        }
    }

    @Override
    @Transactional
    public PersonalCollection addToCollection(UUID collectionId, UUID workId, long expectedVersion) {
        var identity = identity();
        var collection = requireCollectionRow(collectionId, identity);
        requireVersion(collection.version(), expectedVersion);
        requireWorkVisible(workId, identity);
        var position = collection.ordered() ? nextPosition(collectionId) : null;
        var inserted = jdbc.sql("""
                INSERT INTO personal_collection_work (collection_id, work_id, position)
                VALUES (:collectionId, :workId, :position)
                ON CONFLICT (collection_id, work_id) DO NOTHING
                """)
                .param("collectionId", collectionId)
                .param("workId", workId)
                .param("position", position)
                .update();
        if (inserted > 0) {
            incrementCollectionVersion(collectionId, identity.userId(), expectedVersion);
        }
        return requireCollection(collectionId, identity);
    }

    @Override
    @Transactional
    public PersonalCollection removeFromCollection(UUID collectionId, UUID workId, long expectedVersion) {
        var identity = identity();
        var collection = requireCollectionRow(collectionId, identity);
        requireVersion(collection.version(), expectedVersion);
        requireWorkVisible(workId, identity);
        var deleted = jdbc.sql("""
                DELETE FROM personal_collection_work
                WHERE collection_id = :collectionId AND work_id = :workId
                """)
                .param("collectionId", collectionId)
                .param("workId", workId)
                .update();
        if (deleted > 0) {
            incrementCollectionVersion(collectionId, identity.userId(), expectedVersion);
            if (collection.ordered()) {
                compactPositions(collectionId);
            }
        }
        return requireCollection(collectionId, identity);
    }

    private Identity identity() {
        var access = accessContext.current();
        if (access.unrestricted()) {
            jdbc.sql("""
                    INSERT INTO user_account (
                        id, username, normalized_username, password_hash, owner, enabled
                    ) VALUES (
                        :id, '__loopback_development__', '__loopback_development__',
                        '{noop}disabled', FALSE, FALSE
                    ) ON CONFLICT (id) DO NOTHING
                    """).param("id", LOOPBACK_USER_ID).update();
            return new Identity(LOOPBACK_USER_ID, true);
        }
        return new Identity(access.userId(), false);
    }

    private void requireWorkVisible(UUID workId, Identity identity) {
        var visible = jdbc.sql("SELECT yurlib_user_can_access_work(:userId, :workId, :unrestricted)")
                .param("userId", identity.userId())
                .param("workId", workId)
                .param("unrestricted", identity.unrestricted())
                .query(Boolean.class)
                .single();
        if (!visible) {
            throw notFound();
        }
    }

    private void requireContributorVisible(UUID contributorId, Identity identity) {
        var visible = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM work_contributor linked
                    WHERE linked.contributor_id = :contributorId
                      AND yurlib_user_can_access_work(:userId, linked.work_id, :unrestricted)
                )
                """)
                .param("contributorId", contributorId)
                .param("userId", identity.userId())
                .param("unrestricted", identity.unrestricted())
                .query(Boolean.class)
                .single();
        if (!visible) {
            throw notFound();
        }
    }

    private void requireEditionInWork(UUID workId, UUID editionId) {
        if (editionId == null) {
            return;
        }
        var matches = jdbc.sql("SELECT EXISTS (SELECT 1 FROM edition WHERE id = :id AND work_id = :workId)")
                .param("id", editionId)
                .param("workId", workId)
                .query(Boolean.class)
                .single();
        if (!matches) {
            throw new PersonalLibraryFailure(
                    EDITION_NOT_IN_WORK, "The completed edition does not belong to the selected Work.");
        }
    }

    private ReadState readState(UUID userId, UUID workId) {
        return jdbc.sql("""
                SELECT work_id, completed_edition_id, completed_at, version
                FROM user_work_read_state
                WHERE user_id = :userId AND work_id = :workId
                """)
                .param("userId", userId)
                .param("workId", workId)
                .query((row, rowNumber) -> new ReadState(
                        row.getObject("work_id", UUID.class),
                        row.getObject("completed_edition_id", UUID.class),
                        row.getTimestamp("completed_at").toInstant(),
                        row.getLong("version")))
                .single();
    }

    private PersonalCollection requireCollection(UUID id, Identity identity) {
        return collection(requireCollectionRow(id, identity), identity);
    }

    private CollectionRow requireCollectionRow(UUID id, Identity identity) {
        return jdbc.sql("""
                SELECT id, name, ordered, version
                FROM personal_collection
                WHERE id = :id AND user_id = :userId
                """)
                .param("id", id)
                .param("userId", identity.userId())
                .query(CollectionRow.class)
                .optional()
                .orElseThrow(JdbcPersonalLibraryStore::notFound);
    }

    private PersonalCollection collection(CollectionRow row, Identity identity) {
        var works = jdbc.sql("""
                SELECT member.work_id,
                       COALESCE(display.display_value, work.provisional_title) AS title,
                       member.position,
                       member.added_at
                FROM personal_collection_work member
                JOIN work ON work.id = member.work_id
                LEFT JOIN catalog_metadata_display display
                  ON display.subject_type = 'WORK'
                 AND display.subject_id = work.id
                 AND display.field_name = 'title'
                WHERE member.collection_id = :collectionId
                  AND yurlib_user_can_access_work(:userId, member.work_id, :unrestricted)
                ORDER BY member.position NULLS LAST, member.added_at, member.work_id
                """)
                .param("collectionId", row.id())
                .param("userId", identity.userId())
                .param("unrestricted", identity.unrestricted())
                .query((result, rowNumber) -> new CollectionWork(
                        result.getObject("work_id", UUID.class),
                        result.getString("title"),
                        result.getObject("position", Integer.class),
                        result.getTimestamp("added_at").toInstant()))
                .list();
        return new PersonalCollection(row.id(), row.name(), row.ordered(), row.version(), works);
    }

    private Integer nextPosition(UUID collectionId) {
        return jdbc.sql("""
                SELECT COALESCE(max(position), -1) + 1
                FROM personal_collection_work
                WHERE collection_id = :collectionId
                """)
                .param("collectionId", collectionId)
                .query(Integer.class)
                .single();
    }

    private void incrementCollectionVersion(UUID collectionId, UUID userId, long expectedVersion) {
        var updated = jdbc.sql("""
                UPDATE personal_collection
                SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND user_id = :userId AND version = :expectedVersion
                """)
                .param("id", collectionId)
                .param("userId", userId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated == 0) {
            throw conflict();
        }
    }

    private void compactPositions(UUID collectionId) {
        var positions = jdbc.sql("""
                SELECT work_id
                FROM personal_collection_work
                WHERE collection_id = :collectionId
                ORDER BY position, added_at, work_id
                """)
                .param("collectionId", collectionId)
                .query(UUID.class)
                .list();
        jdbc.sql("""
                UPDATE personal_collection_work
                SET position = position + 1000000
                WHERE collection_id = :collectionId AND position IS NOT NULL
                """).param("collectionId", collectionId).update();
        for (var index = 0; index < positions.size(); index++) {
            jdbc.sql("""
                    UPDATE personal_collection_work SET position = :position
                    WHERE collection_id = :collectionId AND work_id = :workId
                    """)
                    .param("position", index)
                    .param("collectionId", collectionId)
                    .param("workId", positions.get(index))
                    .update();
        }
    }

    private void distinguishMissingCollection(UUID id, Identity identity) {
        var exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM personal_collection WHERE id = :id AND user_id = :userId)")
                .param("id", id)
                .param("userId", identity.userId())
                .query(Boolean.class)
                .single();
        if (exists) {
            throw conflict();
        }
        throw notFound();
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw conflict();
        }
    }

    private static String displayName(String name) {
        return name.strip();
    }

    private static String normalizedName(String name) {
        return displayName(name).toLowerCase(Locale.ROOT);
    }

    private static PersonalLibraryFailure notFound() {
        return new PersonalLibraryFailure(
                PERSONAL_ITEM_NOT_FOUND, "The requested personal library item was not found.");
    }

    private static PersonalLibraryFailure conflict() {
        return new PersonalLibraryFailure(
                PERSONAL_VERSION_CONFLICT, "The personal library state changed. Reload it and try again.");
    }

    private static PersonalLibraryFailure collectionNameExists(DataIntegrityViolationException failure) {
        return new PersonalLibraryFailure(
                COLLECTION_NAME_EXISTS, "A collection with that name already exists.", failure);
    }

    private record Identity(UUID userId, boolean unrestricted) {}

    private record CollectionRow(UUID id, String name, boolean ordered, long version) {}
}
