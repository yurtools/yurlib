package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;
import org.yurlib.server.testing.RepositoryPaths;

class OpenApiContractTest {

    @Test
    void parsesTheVersionOneContractWithoutErrors() {
        var contract = RepositoryPaths.root().resolve("contracts/openapi/yurlib-v1.yaml");
        var options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);

        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getOpenapi()).isEqualTo("3.1.0");
        assertThat(result.getOpenAPI().getPaths().keySet())
                .containsExactlyInAnyOrder(
                        "/api/v1/library-mounts",
                        "/api/v1/session",
                        "/api/v1/session/logout",
                        "/api/v1/library-roots",
                        "/api/v1/library-roots/{rootId}",
                        "/api/v1/library-roots/{rootId}/scans",
                        "/api/v1/jobs/{jobId}",
                        "/api/v1/catalog/works",
                        "/api/v1/assets/{assetId}/content",
                        "/api/v1/admin/users",
                        "/api/v1/admin/users/{userId}/enabled",
                        "/api/v1/admin/users/{userId}/credential",
                        "/api/v1/admin/users/{userId}/capabilities",
                        "/api/v1/admin/users/{userId}/root-denies/{rootId}");
    }
}
