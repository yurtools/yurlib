package org.yurlib.server.library.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yurlib.server.library.application.CoverPreferenceUseCases;
import org.yurlib.server.library.application.LibraryAccessContext;

@RestController
@RequestMapping("/api/v1/curation/works")
public class CoverCurationController {

    private final CoverPreferenceUseCases preferences;
    private final LibraryAccessContext accessContext;

    public CoverCurationController(CoverPreferenceUseCases preferences, LibraryAccessContext accessContext) {
        this.preferences = preferences;
        this.accessContext = accessContext;
    }

    @PutMapping("/{workId}/cover")
    CoverPreferenceUseCases.CoverPreference select(
            @PathVariable UUID workId, @Valid @RequestBody CoverPreferenceRequest request) {
        return preferences.select(
                workId,
                request.sourceAssetId(),
                request.reason(),
                request.expectedVersion(),
                accessContext.current().userId());
    }

    record CoverPreferenceRequest(
            @NotNull UUID sourceAssetId,
            @NotBlank @Size(max = 1_000) String reason,
            @Min(0) long expectedVersion) {}
}
