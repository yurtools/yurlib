package org.yurlib.server.library.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.PersonalLibraryUseCases;

@RestController
@Validated
@RequestMapping("/api/v1/me")
public class PersonalLibraryController {

    private final PersonalLibraryUseCases personalLibrary;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Spring owns the injected application service for the controller lifetime.")
    public PersonalLibraryController(PersonalLibraryUseCases personalLibrary) {
        this.personalLibrary = personalLibrary;
    }

    @GetMapping("/library-state")
    PersonalLibraryUseCases.Snapshot snapshot() {
        return personalLibrary.snapshot();
    }

    @GetMapping("/export")
    PersonalLibraryUseCases.Snapshot export() {
        return personalLibrary.snapshot();
    }

    @PutMapping("/favorite-contributors/{contributorId}")
    PersonalLibraryUseCases.FavoriteContributor addFavorite(@PathVariable UUID contributorId) {
        return personalLibrary.addFavorite(contributorId);
    }

    @DeleteMapping("/favorite-contributors/{contributorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeFavorite(@PathVariable UUID contributorId) {
        personalLibrary.removeFavorite(contributorId);
    }

    @PutMapping("/works/{workId}/read-state")
    PersonalLibraryUseCases.ReadState markRead(@PathVariable UUID workId, @Valid @RequestBody MarkReadRequest request) {
        return personalLibrary.markRead(
                workId, request.completedEditionId(), request.completedAt(), request.expectedVersion());
    }

    @DeleteMapping("/works/{workId}/read-state")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markUnread(@PathVariable UUID workId, @RequestParam @PositiveOrZero long expectedVersion) {
        personalLibrary.markUnread(workId, expectedVersion);
    }

    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    PersonalLibraryUseCases.PersonalCollection createCollection(@Valid @RequestBody CollectionRequest request) {
        return personalLibrary.createCollection(request.name(), request.ordered());
    }

    @PutMapping("/collections/{collectionId}")
    PersonalLibraryUseCases.PersonalCollection updateCollection(
            @PathVariable UUID collectionId, @Valid @RequestBody CollectionUpdateRequest request) {
        return personalLibrary.updateCollection(
                collectionId, request.name(), request.ordered(), request.expectedVersion());
    }

    @DeleteMapping("/collections/{collectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCollection(@PathVariable UUID collectionId, @RequestParam @PositiveOrZero long expectedVersion) {
        personalLibrary.deleteCollection(collectionId, expectedVersion);
    }

    @PutMapping("/collections/{collectionId}/works/{workId}")
    PersonalLibraryUseCases.PersonalCollection addToCollection(
            @PathVariable UUID collectionId,
            @PathVariable UUID workId,
            @Valid @RequestBody CollectionVersionRequest request) {
        return personalLibrary.addToCollection(collectionId, workId, request.expectedVersion());
    }

    @DeleteMapping("/collections/{collectionId}/works/{workId}")
    PersonalLibraryUseCases.PersonalCollection removeFromCollection(
            @PathVariable UUID collectionId,
            @PathVariable UUID workId,
            @RequestParam @PositiveOrZero long expectedVersion) {
        return personalLibrary.removeFromCollection(collectionId, workId, expectedVersion);
    }

    record MarkReadRequest(
            UUID completedEditionId,
            @NotNull Instant completedAt,
            @Min(-1) long expectedVersion) {}

    record CollectionRequest(@NotBlank @Size(max = 200) String name, boolean ordered) {}

    record CollectionUpdateRequest(
            @NotBlank @Size(max = 200) String name,
            boolean ordered,
            @PositiveOrZero long expectedVersion) {}

    record CollectionVersionRequest(@PositiveOrZero long expectedVersion) {}
}
