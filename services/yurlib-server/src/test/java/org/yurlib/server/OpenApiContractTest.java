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
                        "/api/v1/jobs/{jobId}/cancel",
                        "/api/v1/catalog/works",
                        "/api/v1/catalog/works/{workId}/cover",
                        "/api/v1/curation/works/{workId}",
                        "/api/v1/curation/works/{workId}/title",
                        "/api/v1/curation/works/{workId}/title/undo",
                        "/api/v1/curation/works/{workId}/tags",
                        "/api/v1/curation/works/{workId}/cover",
                        "/api/v1/curation/reviews",
                        "/api/v1/curation/contributors/{contributorId}",
                        "/api/v1/curation/reviews/{reviewId}/dismiss",
                        "/api/v1/curation/recovery/preview",
                        "/api/v1/curation/recovery/merges",
                        "/api/v1/curation/recovery/merges/{operationId}/split-preview",
                        "/api/v1/curation/recovery/merges/{operationId}/undo",
                        "/api/v1/curation/recovery/not-same",
                        "/api/v1/curation/recovery/history",
                        "/api/v1/assets/{assetId}/content",
                        "/api/v1/me/library-state",
                        "/api/v1/me/export",
                        "/api/v1/me/favorite-contributors/{contributorId}",
                        "/api/v1/me/works/{workId}/read-state",
                        "/api/v1/me/collections",
                        "/api/v1/me/collections/{collectionId}",
                        "/api/v1/me/collections/{collectionId}/works/{workId}",
                        "/api/v1/admin/users",
                        "/api/v1/admin/users/{userId}",
                        "/api/v1/admin/users/{userId}/enabled",
                        "/api/v1/admin/users/{userId}/credential",
                        "/api/v1/admin/users/{userId}/capabilities",
                        "/api/v1/admin/users/{userId}/root-denies/{rootId}");
    }
}
