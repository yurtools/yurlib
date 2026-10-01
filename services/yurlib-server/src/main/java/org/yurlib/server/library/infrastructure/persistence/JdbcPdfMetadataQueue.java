package org.yurlib.server.library.infrastructure.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.CandidateReconciliationResult;
import org.yurlib.server.library.application.CatalogReconciliation;
import org.yurlib.server.library.application.CatalogStore;
import org.yurlib.server.library.application.ExtractedBookMetadata;
import org.yurlib.server.library.application.MetadataExtractionResult;
import org.yurlib.server.library.application.PdfMetadataQueue;
import org.yurlib.server.library.application.PdfWorkerClaim;
import org.yurlib.server.library.application.PdfWorkerJobService;
import org.yurlib.server.library.application.PdfWorkerResult;
import org.yurlib.server.library.application.ScanDiscovery;
import org.yurlib.server.library.infrastructure.config.PdfWorkerProperties;

@Component
public class JdbcPdfMetadataQueue implements PdfMetadataQueue, PdfWorkerJobService {

    private static final long MAXIMUM_SOURCE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAXIMUM_SELECTED_BYTES = 64 * 1024;
    private static final int MAXIMUM_COLLECTION_VALUES = 256;
    private static final int MAXIMUM_OBSERVATION_FIELDS = 256;
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final JdbcClient jdbc;
    private final CatalogStore catalog;
    private final PdfWorkerProperties properties;
    private final Clock clock;

    public JdbcPdfMetadataQueue(JdbcClient jdbc, CatalogStore catalog, PdfWorkerProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "Transactional persistence failures must propagate after the staged file is removed.")
    public CandidateReconciliationResult stageAndQueue(
            UUID rootId, UUID scanJobId, String extractionVersion, ScanDiscovery.Candidate candidate) {
        var existing = findMatching(rootId, extractionVersion, candidate);
        if (existing.isPresent() && !"FAILED_SAFE".equals(existing.get().state())) {
            catalog.markSeen(rootId, scanJobId, candidate.normalizedRelativePath());
            return CandidateReconciliationResult.processed();
        }

        StagedPdf staged;
        try {
            staged = stage(candidate.containedFile(), candidate.byteSize());
        } catch (UnstableFileException failure) {
            return CandidateReconciliationResult.deferred(
                    MetadataExtractionResult.ErrorCode.FILE_UNSTABLE.name(), "The PDF changed while it was staged.");
        } catch (UnsupportedPdfException failure) {
            return CandidateReconciliationResult.failed(
                    MetadataExtractionResult.ErrorCode.UNSUPPORTED_FORMAT.name(),
                    "The file does not contain a PDF signature in the allowed prefix.");
        } catch (IOException failure) {
            return CandidateReconciliationResult.failed(
                    MetadataExtractionResult.ErrorCode.CORRUPT_ASSET.name(), "The PDF could not be staged safely.");
        }

        try {
            var jobId = existing.map(MatchingJob::id).orElse(staged.id());
            if (existing.isPresent()) {
                resetFailedJob(jobId, scanJobId, staged);
                deleteQuietly(existing.get().stagedFileName());
            } else {
                insertJob(jobId, rootId, scanJobId, extractionVersion, candidate, staged);
            }
            var pending = new ExtractedBookMetadata(
                    ExtractedBookMetadata.Format.PDF,
                    null,
                    List.of(),
                    null,
                    Map.of(),
                    Map.of("pdf:metadata-state", List.of("PENDING")),
                    candidate.byteSize(),
                    candidate.modifiedAt(),
                    "pdf-metadata-queue",
                    "1");
            catalog.reconcile(new CatalogReconciliation(
                    rootId,
                    scanJobId,
                    candidate.normalizedRelativePath(),
                    candidate.fileKey(),
                    extractionVersion,
                    pending,
                    clock.instant(),
                    CatalogReconciliation.MetadataState.PENDING));
            return CandidateReconciliationResult.processed();
        } catch (RuntimeException failure) {
            deleteQuietly(staged.fileName());
            throw failure;
        }
    }

    @Override
    @Transactional
    public Optional<PdfWorkerClaim> claim() {
        failExhaustedExpiredJobs();
        var now = clock.instant();
        var jobId = jdbc.sql("""
                SELECT id
                FROM pdf_metadata_job
                WHERE attempt_count < 3
                  AND (state = 'QUEUED' OR (state = 'RUNNING' AND lease_expires_at <= :now))
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """).param("now", timestamp(now)).query(UUID.class).optional();
        if (jobId.isEmpty()) {
            return Optional.empty();
        }
        var leaseToken = UUID.randomUUID();
        var leaseExpiresAt = now.plus(properties.leaseDuration());
        return jdbc.sql("""
                UPDATE pdf_metadata_job
                SET state = 'RUNNING',
                    attempt_count = attempt_count + 1,
                    lease_token = :leaseToken,
                    lease_expires_at = :leaseExpiresAt,
                    started_at = COALESCE(started_at, :now),
                    error_code = NULL,
                    safe_diagnostic = NULL
                WHERE id = :id
                RETURNING id, lease_token, byte_size, source_sha256 AS sha256
                """)
                .param("leaseToken", leaseToken)
                .param("leaseExpiresAt", timestamp(leaseExpiresAt))
                .param("now", timestamp(now))
                .param("id", jobId.get())
                .query(PdfWorkerClaim.class)
                .optional();
    }

    @Override
    public Path leasedInput(UUID jobId, UUID leaseToken) {
        var fileName = jdbc.sql("""
                SELECT staged_file_name
                FROM pdf_metadata_job
                WHERE id = :id
                  AND state = 'RUNNING'
                  AND lease_token = :leaseToken
                  AND lease_expires_at > :now
                """)
                .param("id", jobId)
                .param("leaseToken", leaseToken)
                .param("now", timestamp(clock.instant()))
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("The PDF metadata lease is invalid or expired."));
        var root = stagingRoot();
        var input = root.resolve(fileName).normalize();
        if (!root.equals(input.getParent()) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The staged PDF is unavailable.");
        }
        return input;
    }

    @Override
    @Transactional
    public void complete(UUID jobId, UUID leaseToken, PdfWorkerResult result) {
        var job = requireLeasedJob(jobId, leaseToken);
        validateResult(result);
        var completedAt = clock.instant();
        if (result.state() == PdfWorkerResult.State.EXTRACTED) {
            var observations = new java.util.LinkedHashMap<String, List<String>>(result.observations());
            observations.put("pdf:metadata-state", List.of("READY"));
            observations.put("pdf:page-count", List.of(result.pageCount().toString()));
            var metadata = new ExtractedBookMetadata(
                    ExtractedBookMetadata.Format.PDF,
                    result.title(),
                    result.contributors(),
                    result.language(),
                    result.identifiers(),
                    observations,
                    job.byteSize(),
                    job.modifiedAt(),
                    result.parserName(),
                    result.parserVersion());
            catalog.reconcile(new CatalogReconciliation(
                    job.rootId(),
                    job.scanJobId(),
                    job.path(),
                    job.fileKey(),
                    job.extractionVersion(),
                    metadata,
                    completedAt,
                    CatalogReconciliation.MetadataState.READY));
            finishJob(jobId, "SUCCEEDED", null, null, completedAt);
        } else {
            catalog.markMetadataState(job.rootId(), job.path(), CatalogReconciliation.MetadataState.FAILED_SAFE);
            finishJob(jobId, "FAILED_SAFE", result.errorCode(), result.safeDiagnostic(), completedAt);
        }
        deleteQuietly(job.stagedFileName());
    }

    private Optional<MatchingJob> findMatching(
            UUID rootId, String extractionVersion, ScanDiscovery.Candidate candidate) {
        return jdbc.sql("""
                SELECT id, state, staged_file_name
                FROM pdf_metadata_job
                WHERE library_root_id = :rootId
                  AND normalized_relative_path = :path
                  AND byte_size = :byteSize
                  AND modified_at = :modifiedAt
                  AND extraction_version = :extractionVersion
                """)
                .param("rootId", rootId)
                .param("path", candidate.normalizedRelativePath())
                .param("byteSize", candidate.byteSize())
                .param("modifiedAt", timestamp(candidate.modifiedAt()))
                .param("extractionVersion", extractionVersion)
                .query(MatchingJob.class)
                .optional();
    }

    private void insertJob(
            UUID jobId,
            UUID rootId,
            UUID scanJobId,
            String extractionVersion,
            ScanDiscovery.Candidate candidate,
            StagedPdf staged) {
        jdbc.sql("""
                INSERT INTO pdf_metadata_job (
                    id, library_root_id, scan_job_id, normalized_relative_path, file_key,
                    byte_size, modified_at, extraction_version, staged_file_name,
                    source_sha256, state, created_at
                ) VALUES (
                    :id, :rootId, :scanJobId, :path, :fileKey,
                    :byteSize, :modifiedAt, :extractionVersion, :stagedFileName,
                    :sha256, 'QUEUED', :createdAt
                )
                """)
                .param("id", jobId)
                .param("rootId", rootId)
                .param("scanJobId", scanJobId)
                .param("path", candidate.normalizedRelativePath())
                .param("fileKey", candidate.fileKey())
                .param("byteSize", candidate.byteSize())
                .param("modifiedAt", timestamp(candidate.modifiedAt()))
                .param("extractionVersion", extractionVersion)
                .param("stagedFileName", staged.fileName())
                .param("sha256", staged.sha256())
                .param("createdAt", timestamp(clock.instant()))
                .update();
    }

    private void resetFailedJob(UUID jobId, UUID scanJobId, StagedPdf staged) {
        jdbc.sql("""
                UPDATE pdf_metadata_job
                SET scan_job_id = :scanJobId,
                    staged_file_name = :stagedFileName,
                    source_sha256 = :sha256,
                    state = 'QUEUED',
                    attempt_count = 0,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    error_code = NULL,
                    safe_diagnostic = NULL,
                    started_at = NULL,
                    completed_at = NULL,
                    created_at = :createdAt
                WHERE id = :id AND state = 'FAILED_SAFE'
                """)
                .param("scanJobId", scanJobId)
                .param("stagedFileName", staged.fileName())
                .param("sha256", staged.sha256())
                .param("createdAt", timestamp(clock.instant()))
                .param("id", jobId)
                .update();
    }

    private JobRow requireLeasedJob(UUID jobId, UUID leaseToken) {
        return jdbc.sql("""
                SELECT id, library_root_id AS root_id, scan_job_id,
                       normalized_relative_path AS path, file_key, byte_size,
                       modified_at, extraction_version, staged_file_name
                FROM pdf_metadata_job
                WHERE id = :id
                  AND state = 'RUNNING'
                  AND lease_token = :leaseToken
                  AND lease_expires_at > :now
                FOR UPDATE
                """)
                .param("id", jobId)
                .param("leaseToken", leaseToken)
                .param("now", timestamp(clock.instant()))
                .query(JobRow.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("The PDF metadata lease is invalid or expired."));
    }

    private void finishJob(UUID jobId, String state, String errorCode, String diagnostic, Instant completedAt) {
        jdbc.sql("""
                UPDATE pdf_metadata_job
                SET state = :state,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    error_code = :errorCode,
                    safe_diagnostic = :diagnostic,
                    completed_at = :completedAt
                WHERE id = :id
                """)
                .param("state", state)
                .param("errorCode", errorCode)
                .param("diagnostic", diagnostic)
                .param("completedAt", timestamp(completedAt))
                .param("id", jobId)
                .update();
    }

    private void failExhaustedExpiredJobs() {
        jdbc.sql("""
                WITH exhausted AS (
                    UPDATE pdf_metadata_job
                    SET state = 'FAILED_SAFE',
                        lease_token = NULL,
                        lease_expires_at = NULL,
                        error_code = 'PARSE_LIMIT_EXCEEDED',
                        safe_diagnostic = 'The isolated PDF worker exhausted its retry limit.',
                        completed_at = :now
                    WHERE state = 'RUNNING'
                      AND lease_expires_at <= :now
                      AND attempt_count >= 3
                    RETURNING library_root_id, normalized_relative_path
                )
                UPDATE asset
                SET metadata_state = 'FAILED_SAFE'
                FROM asset_location location, exhausted
                WHERE location.asset_id = asset.id
                  AND location.library_root_id = exhausted.library_root_id
                  AND location.normalized_relative_path = exhausted.normalized_relative_path
                """).param("now", timestamp(clock.instant())).update();
    }

    private StagedPdf stage(Path source, long expectedSize)
            throws IOException, UnstableFileException, UnsupportedPdfException {
        if (expectedSize <= 0 || expectedSize > MAXIMUM_SOURCE_BYTES) {
            throw new IOException("Source size is outside the PDF staging limit.");
        }
        var before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.size() != expectedSize) {
            throw new UnstableFileException();
        }
        var id = UUID.randomUUID();
        var fileName = id + ".pdf";
        var destination = stagingRoot().resolve(fileName);
        var digest = sha256();
        var prefix = new byte[1024];
        var prefixLength = 0;
        long copied = 0;
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                var output =
                        Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied = Math.addExact(copied, read);
                if (copied > expectedSize) {
                    throw new UnstableFileException();
                }
                var prefixCopy = Math.min(read, prefix.length - prefixLength);
                if (prefixCopy > 0) {
                    System.arraycopy(buffer, 0, prefix, prefixLength, prefixCopy);
                    prefixLength += prefixCopy;
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
        var after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (copied != expectedSize
                || after.size() != before.size()
                || !after.lastModifiedTime().equals(before.lastModifiedTime())
                || !java.util.Objects.equals(after.fileKey(), before.fileKey())) {
            Files.deleteIfExists(destination);
            throw new UnstableFileException();
        }
        if (indexOf(prefix, prefixLength, PDF_SIGNATURE) < 0) {
            Files.deleteIfExists(destination);
            throw new UnsupportedPdfException();
        }
        try {
            setReadOnly(destination);
        } catch (IOException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
        return new StagedPdf(id, fileName, HexFormat.of().formatHex(digest.digest()));
    }

    private Path stagingRoot() {
        try {
            var configured = properties.stagingDirectory();
            Files.createDirectories(configured);
            if (Files.isSymbolicLink(configured)) {
                throw new IOException("The PDF staging directory must not be a symbolic link.");
            }
            return configured.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("The PDF staging directory is unavailable.", failure);
        }
    }

    private void deleteQuietly(String fileName) {
        try {
            var root = stagingRoot();
            var path = root.resolve(fileName).normalize();
            if (root.equals(path.getParent())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // Expired staging cleanup may retry this bounded file later.
        }
    }

    private static void validateResult(PdfWorkerResult result) {
        if (result == null || result.state() == null) {
            throw new IllegalArgumentException("The PDF worker result is invalid.");
        }
        selected(result.parserName());
        selected(result.parserVersion());
        if (!"apache-pdfbox".equals(result.parserName()) || !"3.0.8-1".equals(result.parserVersion())) {
            throw new IllegalArgumentException("The PDF worker parser version is not accepted.");
        }
        if (result.state() == PdfWorkerResult.State.FAILED) {
            if (!Set.of("ENCRYPTED_ASSET", "CORRUPT_ASSET", "UNSUPPORTED_FORMAT", "PARSE_LIMIT_EXCEEDED")
                    .contains(result.errorCode())) {
                throw new IllegalArgumentException("The PDF worker error code is invalid.");
            }
            selected(result.safeDiagnostic());
            return;
        }
        if (result.errorCode() != null
                || result.safeDiagnostic() != null
                || result.pageCount() == null
                || result.pageCount() < 1
                || result.pageCount() > 100_000) {
            throw new IllegalArgumentException("The extracted PDF worker result is invalid.");
        }
        selectedNullable(result.title());
        selectedNullable(result.language());
        validateValues(result.contributors());
        if (result.identifiers().size() > MAXIMUM_COLLECTION_VALUES) {
            throw new IllegalArgumentException("The PDF worker identifier limit was exceeded.");
        }
        result.identifiers().forEach((key, value) -> {
            selected(key);
            selected(value);
        });
        if (result.observations().size() > MAXIMUM_OBSERVATION_FIELDS) {
            throw new IllegalArgumentException("The PDF worker observation-field limit was exceeded.");
        }
        result.observations().forEach((key, values) -> {
            selected(key);
            validateValues(values);
        });
    }

    private static void validateValues(List<String> values) {
        if (values.size() > MAXIMUM_COLLECTION_VALUES) {
            throw new IllegalArgumentException("The PDF worker collection limit was exceeded.");
        }
        values.forEach(JdbcPdfMetadataQueue::selected);
    }

    private static void selectedNullable(String value) {
        if (value != null) {
            selected(value);
        }
    }

    private static void selected(String value) {
        if (value == null
                || value.isBlank()
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAXIMUM_SELECTED_BYTES) {
            throw new IllegalArgumentException("A PDF worker selected value is invalid.");
        }
    }

    private static int indexOf(byte[] haystack, int length, byte[] needle) {
        return new String(haystack, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1)
                .indexOf(new String(needle, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", impossible);
        }
    }

    private static void setReadOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ));
        } catch (IOException | UnsupportedOperationException ignored) {
            if (!path.toFile().setReadOnly()) {
                throw new IOException("The staged PDF could not be made read-only.");
            }
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record StagedPdf(UUID id, String fileName, String sha256) {}

    private record MatchingJob(UUID id, String state, String stagedFileName) {}

    private record JobRow(
            UUID id,
            UUID rootId,
            UUID scanJobId,
            String path,
            String fileKey,
            long byteSize,
            Instant modifiedAt,
            String extractionVersion,
            String stagedFileName) {}

    private static final class UnstableFileException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class UnsupportedPdfException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
