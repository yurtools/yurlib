package org.yurlib.worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

final class WorkerClient implements AutoCloseable {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final URI server;
    private final String authorization;
    private final HttpClient http;

    WorkerClient(URI server, String token) {
        this.server = server.toString().endsWith("/") ? server : URI.create(server + "/");
        this.authorization = "Bearer " + token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    Optional<WorkerClaim> claim() throws IOException, InterruptedException {
        var request = authorized(HttpRequest.newBuilder(resolve("internal/v1/pdf-metadata/jobs/claim")))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() == 204) {
                return Optional.empty();
            }
            requireStatus(response.statusCode(), 200);
            return Optional.of(WorkerJson.MAPPER.readValue(body, WorkerClaim.class));
        }
    }

    Optional<CoverClaim> claimCover() throws IOException, InterruptedException {
        var request = authorized(HttpRequest.newBuilder(resolve("internal/v1/covers/jobs/claim")))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() == 204) {
                return Optional.empty();
            }
            requireStatus(response.statusCode(), 200);
            return Optional.of(WorkerJson.MAPPER.readValue(body, CoverClaim.class));
        }
    }

    Optional<ConversionClaim> claimConversion() throws IOException, InterruptedException {
        var request = authorized(HttpRequest.newBuilder(resolve("internal/v1/conversions/jobs/claim")))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() == 204) {
                return Optional.empty();
            }
            requireStatus(response.statusCode(), 200);
            return Optional.of(WorkerJson.MAPPER.readValue(body, ConversionClaim.class));
        }
    }

    void download(WorkerClaim claim, Path target) throws IOException, InterruptedException {
        download(
                "internal/v1/pdf-metadata/jobs/" + claim.id() + "/input",
                claim.leaseToken().toString(),
                claim.byteSize(),
                claim.sha256(),
                target);
    }

    void download(CoverClaim claim, Path target) throws IOException, InterruptedException {
        download(
                "internal/v1/covers/jobs/" + claim.id() + "/input",
                claim.leaseToken().toString(),
                claim.byteSize(),
                claim.sha256(),
                target);
    }

    void download(ConversionClaim claim, Path target) throws IOException, InterruptedException {
        download(
                "internal/v1/conversions/jobs/" + claim.id() + "/input",
                claim.leaseToken().toString(),
                claim.byteSize(),
                claim.sha256(),
                target);
    }

    boolean heartbeat(ConversionClaim claim) throws IOException, InterruptedException {
        var request = authorized(
                        HttpRequest.newBuilder(resolve("internal/v1/conversions/jobs/" + claim.id() + "/heartbeat")))
                .header("X-Yurlib-Lease-Token", claim.leaseToken().toString())
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        requireStatus(response.statusCode(), 200);
        try (var body = response.body()) {
            return WorkerJson.MAPPER
                    .readTree(body)
                    .path("cancellationRequested")
                    .asBoolean();
        }
    }

    void upload(ConversionClaim claim, Path output, String sha256) throws IOException, InterruptedException {
        var request = authorized(
                        HttpRequest.newBuilder(resolve("internal/v1/conversions/jobs/" + claim.id() + "/output")))
                .header("X-Yurlib-Lease-Token", claim.leaseToken().toString())
                .header("X-Yurlib-Content-SHA256", sha256)
                .header("Content-Type", "application/epub+zip")
                .timeout(Duration.ofMinutes(10))
                .PUT(HttpRequest.BodyPublishers.ofFile(output))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        requireStatus(response.statusCode(), 204);
    }

    private void download(String path, String leaseToken, long expectedSize, String expectedHash, Path target)
            throws IOException, InterruptedException {
        var request = authorized(HttpRequest.newBuilder(resolve(path)))
                .header("X-Yurlib-Lease-Token", leaseToken)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        requireStatus(response.statusCode(), 200);
        var digest = sha256();
        long copied = 0;
        try (var input = response.body();
                var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied = Math.addExact(copied, read);
                if (copied > expectedSize) {
                    throw new IOException("Staged worker input exceeded its declared size.");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        var actualHash = HexFormat.of().formatHex(digest.digest());
        if (copied != expectedSize
                || !MessageDigest.isEqual(
                        actualHash.getBytes(StandardCharsets.US_ASCII),
                        expectedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Staged worker input integrity verification failed.");
        }
    }

    void complete(WorkerClaim claim, PdfMetadataResult result) throws IOException, InterruptedException {
        var request = authorized(
                        HttpRequest.newBuilder(resolve("internal/v1/pdf-metadata/jobs/" + claim.id() + "/result")))
                .header("X-Yurlib-Lease-Token", claim.leaseToken().toString())
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(WorkerJson.MAPPER.writeValueAsBytes(result)))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        requireStatus(response.statusCode(), 204);
    }

    void complete(CoverClaim claim, CoverResult result) throws IOException, InterruptedException {
        var request = authorized(HttpRequest.newBuilder(resolve("internal/v1/covers/jobs/" + claim.id() + "/result")))
                .header("X-Yurlib-Lease-Token", claim.leaseToken().toString())
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(WorkerJson.MAPPER.writeValueAsBytes(result)))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        requireStatus(response.statusCode(), 204);
    }

    void complete(ConversionClaim claim, ConversionResult result) throws IOException, InterruptedException {
        var request = authorized(
                        HttpRequest.newBuilder(resolve("internal/v1/conversions/jobs/" + claim.id() + "/result")))
                .header("X-Yurlib-Lease-Token", claim.leaseToken().toString())
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(WorkerJson.MAPPER.writeValueAsBytes(result)))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        requireStatus(response.statusCode(), 204);
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return builder.header("Authorization", authorization).header("Accept", "application/json");
    }

    private URI resolve(String path) {
        return server.resolve(path);
    }

    private static void requireStatus(int actual, int expected) throws IOException {
        if (actual != expected) {
            throw new IOException("Internal worker API returned HTTP " + actual + ".");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", impossible);
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
