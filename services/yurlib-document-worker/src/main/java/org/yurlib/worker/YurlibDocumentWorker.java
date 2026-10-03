package org.yurlib.worker;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class YurlibDocumentWorker {

    private static final Duration POLL_DELAY = Duration.ofSeconds(1);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(35);
    private static final String EBOOK_CONVERT = "/opt/calibre/ebook-convert";
    private static final String FB2_ROUTE_VERSION = "calibre-9.15.0-fb2-epub-v1";
    private static final String MOBI_ROUTE_VERSION = "calibre-9.15.0-mobi-epub-v1";
    private static final String EFFECTIVE_SETTINGS =
            "{\"epubVersion\":3,\"defaultCover\":false,\"outputProfile\":\"generic_eink\"}";

    private YurlibDocumentWorker() {}

    public static void main(String[] arguments) throws IOException, InterruptedException {
        var configuration = Configuration.fromEnvironment();
        Files.createDirectories(configuration.workDirectory());
        try (var client = new WorkerClient(configuration.server(), configuration.token())) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!runConversionOnce(client, configuration.workDirectory())
                            && !runCoverOnce(client, configuration.workDirectory())) {
                        runOnce(client, configuration.workDirectory());
                    }
                } catch (IOException failure) {
                    System.err.println("The internal document worker API is temporarily unavailable.");
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
        var jobParent = Objects.requireNonNull(jobDirectory.getParent(), "The job must have a parent path.");
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

    static boolean runConversionOnce(WorkerClient client, Path workRoot) throws IOException, InterruptedException {
        var claimed = client.claimConversion();
        if (claimed.isEmpty()) {
            return false;
        }
        var claim = claimed.get();
        var jobDirectory = safeJobDirectory(workRoot, "conversion-" + claim.id());
        Files.createDirectory(jobDirectory);
        try {
            var extension = claim.sourceFormat().name().toLowerCase(java.util.Locale.ROOT);
            var input = jobDirectory.resolve("input." + extension);
            var output = jobDirectory.resolve("output.epub");
            client.download(claim, input);
            var result = runBoundedConversion(client, claim, input, output);
            if (result.state() == ConversionResult.State.SUCCEEDED) {
                client.upload(claim, output, result.outputSha256());
            }
            client.complete(claim, result);
        } finally {
            deleteJobDirectory(jobDirectory);
        }
        return true;
    }

    @SuppressWarnings("PMD.CloseResource")
    @SuppressFBWarnings(
            value = "COMMAND_INJECTION",
            justification = "The executable, arguments, route enum, and UUID-scoped paths are fixed without a shell.")
    private static ConversionResult runBoundedConversion(
            WorkerClient client, ConversionClaim claim, Path input, Path output)
            throws IOException, InterruptedException {
        if (!supports(claim)) {
            return ConversionResult.failed("CONVERSION_REJECTED", "The conversion route contract was rejected.");
        }
        var jobDirectory = Objects.requireNonNull(input.getParent(), "Conversion input must have a parent directory.");
        var calibreTemporary = jobDirectory.resolve("calibre-temp");
        var calibreConfiguration = jobDirectory.resolve("calibre-config");
        Files.createDirectory(calibreTemporary);
        Files.createDirectory(calibreConfiguration);
        var processBuilder = new ProcessBuilder(
                        EBOOK_CONVERT,
                        input.toString(),
                        output.toString(),
                        "--output-profile=generic_eink",
                        "--epub-version=3",
                        "--no-default-epub-cover")
                .directory(jobDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        processBuilder.environment().put("CALIBRE_TEMP_DIR", calibreTemporary.toString());
        processBuilder.environment().put("CALIBRE_CONFIG_DIRECTORY", calibreConfiguration.toString());
        var process = processBuilder.start();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(claim.deadlineSeconds());
        while (process.isAlive()) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                process.destroyForcibly();
                process.waitFor();
                return ConversionResult.failed("CONVERSION_TIMEOUT", "The conversion deadline was exceeded.");
            }
            if (process.waitFor(Math.min(TimeUnit.NANOSECONDS.toSeconds(remaining) + 1, 20), TimeUnit.SECONDS)) {
                break;
            }
            if (client.heartbeat(claim)) {
                process.destroyForcibly();
                process.waitFor();
                return ConversionResult.cancelled();
            }
        }
        if (process.exitValue() != 0) {
            var code = process.exitValue() == 137 ? "RESOURCE_LIMIT" : "CONVERSION_REJECTED";
            var diagnostic = process.exitValue() == 137
                    ? "The converter exceeded its resource boundary."
                    : "The converter rejected the source safely.";
            return ConversionResult.failed(code, diagnostic);
        }
        if (!Files.isRegularFile(output) || Files.size(output) <= 0) {
            return ConversionResult.failed("OUTPUT_INVALID", "The converter did not produce a usable output.");
        }
        var digest = sha256();
        try (var stream = Files.newInputStream(output)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return ConversionResult.succeeded(HexFormat.of().formatHex(digest.digest()), Files.size(output));
    }

    static boolean supports(ConversionClaim claim) {
        if (!sameEffectiveSettings(claim.effectiveSettings())) {
            return false;
        }
        return switch (claim.route()) {
            case FB2_TO_EPUB_V1 ->
                claim.sourceFormat() == ConversionClaim.SourceFormat.FB2
                        && FB2_ROUTE_VERSION.equals(claim.routeVersion());
            case MOBI_TO_EPUB_V1 ->
                claim.sourceFormat() == ConversionClaim.SourceFormat.MOBI
                        && MOBI_ROUTE_VERSION.equals(claim.routeVersion());
        };
    }

    private static boolean sameEffectiveSettings(String settings) {
        try {
            return WorkerJson.MAPPER.readTree(EFFECTIVE_SETTINGS).equals(WorkerJson.MAPPER.readTree(settings));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", impossible);
        }
    }

    private static Path safeJobDirectory(Path workRoot, String name) throws IOException {
        var directory = workRoot.resolve(name).normalize();
        var parent = Objects.requireNonNull(directory.getParent(), "The job must have a parent path.");
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
