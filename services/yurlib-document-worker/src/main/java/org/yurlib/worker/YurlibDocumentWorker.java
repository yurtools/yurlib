package org.yurlib.worker;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public final class YurlibDocumentWorker {

    private static final Duration POLL_DELAY = Duration.ofSeconds(1);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(35);

    private YurlibDocumentWorker() {}

    public static void main(String[] arguments) throws IOException, InterruptedException {
        var configuration = Configuration.fromEnvironment();
        Files.createDirectories(configuration.workDirectory());
        try (var client = new WorkerClient(configuration.server(), configuration.token())) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!runCoverOnce(client, configuration.workDirectory())) {
                        runOnce(client, configuration.workDirectory());
                    }
                } catch (IOException failure) {
                    System.err.println("The internal PDF worker API is temporarily unavailable.");
                }
                Thread.sleep(POLL_DELAY);
            }
        }
    }

    static boolean runOnce(WorkerClient client, Path workRoot) throws IOException, InterruptedException {
        var claimed = client.claim();
        if (claimed.isEmpty()) {
            return false;
        }
        var claim = claimed.get();
        var jobDirectory = workRoot.resolve(claim.id().toString()).normalize();
        var jobParent = java.util.Objects.requireNonNull(jobDirectory.getParent(), "The job must have a parent path.");
        if (!jobParent.equals(workRoot.toAbsolutePath().normalize())) {
            throw new IOException("Worker job path escaped its work root.");
        }
        Files.createDirectory(jobDirectory);
        try {
            var input = jobDirectory.resolve("input.pdf");
            var resultFile = jobDirectory.resolve("result.json");
            client.download(claim, input);
            var result = runBoundedProcess(input, resultFile);
            client.complete(claim, result);
        } finally {
            deleteJobDirectory(jobDirectory);
        }
        return true;
    }

    static boolean runCoverOnce(WorkerClient client, Path workRoot) throws IOException, InterruptedException {
        var claimed = client.claimCover();
        if (claimed.isEmpty()) {
            return false;
        }
        var claim = claimed.get();
        var jobDirectory = safeJobDirectory(workRoot, "cover-" + claim.id());
        Files.createDirectory(jobDirectory);
        try {
            var input = jobDirectory.resolve("input." + claim.format().name().toLowerCase(java.util.Locale.ROOT));
            var resultFile = jobDirectory.resolve("result.json");
            client.download(claim, input);
            var result = runBoundedCoverProcess(input, resultFile, claim.format());
            client.complete(claim, result);
        } finally {
            deleteJobDirectory(jobDirectory);
        }
        return true;
    }

    private static Path safeJobDirectory(Path workRoot, String name) throws IOException {
        var directory = workRoot.resolve(name).normalize();
        var parent = java.util.Objects.requireNonNull(directory.getParent(), "The job must have a parent path.");
        if (!parent.equals(workRoot.toAbsolutePath().normalize())) {
            throw new IOException("Worker job path escaped its work root.");
        }
        return directory;
    }

    @SuppressWarnings("PMD.CloseResource")
    @SuppressFBWarnings(
            value = "COMMAND_INJECTION",
            justification =
                    "The executable and class are fixed, ProcessBuilder bypasses a shell, and paths use a server UUID below the configured work root.")
    private static PdfMetadataResult runBoundedProcess(Path input, Path resultFile)
            throws IOException, InterruptedException {
        var javaExecutable =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(
                        javaExecutable,
                        "-Xmx384m",
                        "-XX:MaxDirectMemorySize=64m",
                        "-Djava.io.tmpdir=" + input.getParent(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        PdfJobProcess.class.getName(),
                        input.toString(),
                        resultFile.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            return PdfMetadataResult.failed("PARSE_LIMIT_EXCEEDED", "The PDF metadata time limit was exceeded.");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(resultFile)) {
            return PdfMetadataResult.failed("UNSUPPORTED_FORMAT", "The isolated PDF parser failed safely.");
        }
        return WorkerJson.MAPPER.readValue(resultFile.toFile(), PdfMetadataResult.class);
    }

    @SuppressWarnings("PMD.CloseResource")
    @SuppressFBWarnings(
            value = "COMMAND_INJECTION",
            justification =
                    "The executable and class are fixed, the format is an enum, and job paths use a server UUID below the configured work root.")
    private static CoverResult runBoundedCoverProcess(Path input, Path resultFile, CoverClaim.Format format)
            throws IOException, InterruptedException {
        var javaExecutable =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(
                        javaExecutable,
                        "-Xmx384m",
                        "-XX:MaxDirectMemorySize=64m",
                        "-Djava.io.tmpdir=" + input.getParent(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        CoverJobProcess.class.getName(),
                        input.toString(),
                        resultFile.toString(),
                        format.name())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            return CoverResult.failed("PARSE_LIMIT_EXCEEDED", "The cover extraction deadline was exceeded.");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(resultFile)) {
            return CoverResult.failed("UNSUPPORTED_FORMAT", "The isolated cover processor failed safely.");
        }
        return WorkerJson.MAPPER.readValue(resultFile.toFile(), CoverResult.class);
    }

    private static void deleteJobDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Configuration(URI server, String token, Path workDirectory) {

        private static Configuration fromEnvironment() {
            var server = required("YURLIB_WORKER_SERVER_URL");
            var token = required("YURLIB_WORKER_TOKEN");
            var work = Path.of(System.getenv().getOrDefault("YURLIB_WORKER_DIRECTORY", "/work"))
                    .toAbsolutePath()
                    .normalize();
            return new Configuration(URI.create(server), token, work);
        }

        private static String required(String name) {
            var value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " must be configured outside the image.");
            }
            return value;
        }
    }
}
