package org.yurlib.server.library.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PersonalLibraryUseCases {

    Snapshot snapshot();

    FavoriteContributor addFavorite(UUID contributorId);

    void removeFavorite(UUID contributorId);

    ReadState markRead(UUID workId, UUID completedEditionId, Instant completedAt, long expectedVersion);

    void markUnread(UUID workId, long expectedVersion);

    PersonalCollection createCollection(String name, boolean ordered);

    PersonalCollection updateCollection(UUID collectionId, String name, boolean ordered, long expectedVersion);

    void deleteCollection(UUID collectionId, long expectedVersion);

    PersonalCollection addToCollection(UUID collectionId, UUID workId, long expectedVersion);

    PersonalCollection removeFromCollection(UUID collectionId, UUID workId, long expectedVersion);

    record Snapshot(
            List<FavoriteContributor> favoriteContributors,
            List<ReadState> readStates,
            List<PersonalCollection> collections) {
        public Snapshot {
            favoriteContributors = List.copyOf(favoriteContributors);
            readStates = List.copyOf(readStates);
            collections = List.copyOf(collections);
        }
    }

    record FavoriteContributor(UUID contributorId, String displayName, Instant favoritedAt) {}

    record ReadState(UUID workId, UUID completedEditionId, Instant completedAt, long version) {}

    record PersonalCollection(UUID id, String name, boolean ordered, long version, List<CollectionWork> works) {
        public PersonalCollection {
            works = List.copyOf(works);
        }
    }

    record CollectionWork(UUID workId, String title, Integer position, Instant addedAt) {}
}
