package org.yurlib.server.library.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.yurlib.server.library.domain.LibraryRoot;

public record CreateLibraryRootRequest(
        @NotBlank @Size(max = 100) String name,

        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}$") String mountAlias,

        @NotNull @Size(max = 1024) String relativePath,
        @NotBlank @Size(min = 16, max = 200) String identityToken,
        @NotNull LibraryRoot.Mode mode,

        @JsonProperty(required = false, defaultValue = "false")
        Boolean defaultForCovers,

        @JsonProperty(required = false, defaultValue = "false")
        Boolean defaultForConversions) {}
