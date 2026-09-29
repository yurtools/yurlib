package org.yurlib.server.library.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLibraryRootRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}$") String mountAlias,
        @NotNull @Size(max = 1024) String relativePath,
        @NotBlank @Size(min = 16, max = 200) String identityToken) {
}
