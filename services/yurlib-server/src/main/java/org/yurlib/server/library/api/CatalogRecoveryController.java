package org.yurlib.server.library.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.CatalogRecovery;
import org.yurlib.server.library.application.LibraryAccessContext;

@RestController
@Validated
@RequestMapping("/api/v1/curation/recovery")
public class CatalogRecoveryController {

    private final CatalogRecovery recovery;
    private final LibraryAccessContext accessContext;

    public CatalogRecoveryController(CatalogRecovery recovery, LibraryAccessContext accessContext) {
        this.recovery = recovery;
        this.accessContext = accessContext;
    }

    @GetMapping("/preview")
    CatalogRecovery.RecoveryPreview preview(
            @RequestParam CatalogRecovery.SubjectType subjectType,
            @RequestParam UUID survivorId,
            @RequestParam UUID sourceId) {
        return recovery.preview(subjectType, survivorId, sourceId);
    }

    @PostMapping("/merges")
    CatalogRecovery.MergeOperation merge(@Valid @RequestBody MergeRequest request) {
        return recovery.merge(
                request.subjectType(),
                request.survivorId(),
                request.sourceId(),
                request.survivorVersion(),
                request.sourceVersion(),
                request.idempotencyKey(),
                request.reason(),
                actorId());
    }

    @GetMapping("/merges/{operationId}/split-preview")
    CatalogRecovery.SplitPreview splitPreview(@PathVariable UUID operationId) {
        return recovery.splitPreview(operationId);
    }

    @PostMapping("/merges/{operationId}/undo")
    CatalogRecovery.MergeOperation undo(@PathVariable UUID operationId, @Valid @RequestBody ReasonRequest request) {
        return recovery.undo(operationId, request.reason(), actorId());
    }

    @PostMapping("/not-same")
    CatalogRecovery.DuplicateDecision markNotSame(@Valid @RequestBody NotSameRequest request) {
        return recovery.markNotSame(
                request.subjectType(),
                request.firstId(),
                request.secondId(),
                request.ruleName(),
                request.ruleVersion(),
                request.reason(),
                actorId());
    }

    @GetMapping("/history")
    List<CatalogRecovery.MergeOperation> history(
            @RequestParam CatalogRecovery.SubjectType subjectType, @RequestParam UUID subjectId) {
        return recovery.history(subjectType, subjectId);
    }

    private UUID actorId() {
        return accessContext.current().userId();
    }

    record MergeRequest(
            @NotNull CatalogRecovery.SubjectType subjectType,
            @NotNull UUID survivorId,
            @NotNull UUID sourceId,
            long survivorVersion,
            long sourceVersion,
            @NotNull UUID idempotencyKey,
            @NotBlank @Size(max = 1_000) String reason) {}

    record NotSameRequest(
            @NotNull CatalogRecovery.SubjectType subjectType,
            @NotNull UUID firstId,
            @NotNull UUID secondId,
            @NotBlank @Size(max = 100) String ruleName,
            @NotBlank @Size(max = 100) String ruleVersion,
            @NotBlank @Size(max = 1_000) String reason) {}

    record ReasonRequest(@NotBlank @Size(max = 1_000) String reason) {}
}
