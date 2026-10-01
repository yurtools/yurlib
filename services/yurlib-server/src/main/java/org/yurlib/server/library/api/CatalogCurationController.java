package org.yurlib.server.library.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.CatalogCuration;
import org.yurlib.server.library.application.LibraryAccessContext;

@RestController
@Validated
@RequestMapping("/api/v1/curation")
public class CatalogCurationController {

    private final CatalogCuration curation;
    private final LibraryAccessContext accessContext;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Spring-managed application ports are intentionally retained as collaborators.")
    public CatalogCurationController(CatalogCuration curation, LibraryAccessContext accessContext) {
        this.curation = curation;
        this.accessContext = accessContext;
    }

    @GetMapping("/works/{workId}")
    CatalogCuration.WorkCuration findWork(@PathVariable UUID workId) {
        return curation.findWork(workId);
    }

    @PutMapping("/works/{workId}/title")
    CatalogCuration.WorkCuration updateTitle(
            @PathVariable UUID workId, @Valid @RequestBody MetadataUpdateRequest request) {
        return curation.updateTitle(workId, request.value(), request.reason(), request.expectedVersion(), actorId());
    }

    @PostMapping("/works/{workId}/title/undo")
    CatalogCuration.WorkCuration undoTitle(
            @PathVariable UUID workId, @Valid @RequestBody VersionedReasonRequest request) {
        return curation.undoTitle(workId, request.reason(), request.expectedVersion(), actorId());
    }

    @PutMapping("/works/{workId}/tags")
    CatalogCuration.WorkCuration replaceTags(@PathVariable UUID workId, @Valid @RequestBody TagUpdateRequest request) {
        return curation.replaceTags(workId, request.tags(), request.reason(), request.expectedVersion(), actorId());
    }

    @GetMapping("/reviews")
    List<CatalogCuration.ReviewItem> findReviews(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return curation.findOpenReviews(limit);
    }

    @PutMapping("/contributors/{contributorId}")
    CatalogCuration.ContributorCuration updateContributor(
            @PathVariable UUID contributorId, @Valid @RequestBody ContributorUpdateRequest request) {
        return curation.updateContributor(
                contributorId,
                request.displayName(),
                request.aliases(),
                request.reason(),
                request.expectedVersion(),
                actorId());
    }

    @PostMapping("/reviews/{reviewId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void dismissReview(@PathVariable UUID reviewId, @Valid @RequestBody ReasonRequest request) {
        curation.dismissReview(reviewId, request.reason(), actorId());
    }

    private UUID actorId() {
        return accessContext.current().userId();
    }

    record MetadataUpdateRequest(
            @NotBlank @Size(max = 2_000) String value,
            @NotBlank @Size(max = 1_000) String reason,
            @Min(0) long expectedVersion) {}

    record VersionedReasonRequest(
            @NotBlank @Size(max = 1_000) String reason,
            @Min(0) long expectedVersion) {}

    record TagUpdateRequest(
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 100) String> tags,
            @NotBlank @Size(max = 1_000) String reason,
            @Min(0) long expectedVersion) {
        TagUpdateRequest {
            tags = tags == null ? null : List.copyOf(tags);
        }
    }

    record ReasonRequest(@NotBlank @Size(max = 1_000) String reason) {}

    record ContributorUpdateRequest(
            @NotBlank @Size(max = 1_000) String displayName,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 1_000) String> aliases,
            @NotBlank @Size(max = 1_000) String reason,
            @Min(0) long expectedVersion) {
        ContributorUpdateRequest {
            aliases = aliases == null ? null : List.copyOf(aliases);
        }
    }
}
