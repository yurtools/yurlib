package org.yurlib.server.library.infrastructure.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.CatalogCurationFailure;
import org.yurlib.server.library.application.CoverContentLocation;
import org.yurlib.server.library.application.CoverContentStore;
import org.yurlib.server.library.application.CoverPreferenceUseCases;
import org.yurlib.server.library.application.CoverQueue;
import org.yurlib.server.library.application.CoverWorkerClaim;
import org.yurlib.server.library.application.CoverWorkerJobService;
import org.yurlib.server.library.application.CoverWorkerResult;
import org.yurlib.server.library.application.LibraryAccessContext;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.domain.Asset;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.infrastructure.config.CoverWorkerProperties;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemRootVerifier;

@Component
public class JdbcCoverQueue implements CoverQueue, CoverWorkerJobService, CoverContentStore, CoverPreferenceUseCases {

    private static final long MAXIMUM_SOURCE_BYTES = 256L * 1024 * 1024;
    private static final int MAXIMUM_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final long MAXIMUM_PIXELS = 8_000_000L;
    private static final String PROCESSOR_VERSION = "yurlib-cover-1";
    private static final Set<String> ERROR_CODES = Set.of(
            "NO_COVER",
            "CORRUPT_ASSET",
            "UNSUPPORTED_FORMAT",
            "ACTIVE_CONTENT_REJECTED",
            "PARSE_LIMIT_EXCEEDED",
            "RENDER_FAILED");

    private final JdbcClient jdbc;
    private final LibraryRootStore roots;
    private final FilesystemRootVerifier verifier;
    private final LibraryAccessContext accessContext;
    private final CoverWorkerProperties properties;
    private final Clock clock;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Spring owns these infrastructure collaborators for the component lifetime.")
    public JdbcCoverQueue(
            JdbcClient jdbc,
            LibraryRootStore roots,
            FilesystemRootVerifier verifier,
            LibraryAccessContext accessContext,
            CoverWorkerProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.roots = roots;
        this.verifier = verifier;
        this.accessContext = accessContext;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "Transactional persistence failures must propagate after staged-input cleanup.")
    public void stageAndQueue(UUID sourceAssetId, Path sourceFile, Asset.Format format, long expectedSize) {
        if (expectedSize <= 0 || expectedSize > MAXIMUM_SOURCE_BYTES) {
            markIndependentFailure(
                    sourceAssetId, "PARSE_LIMIT_EXCEEDED", "The source exceeds the cover staging limit.");
            return;
        }
        var target = findTarget(sourceAssetId);
        if (target.isEmpty() || existingJob(sourceAssetId, target.get().outputRootId())) {
            return;
        }
        StagedSource staged;
        try {
            staged = stage(sourceFile, expectedSize, format);
        } catch (IOException | RuntimeException failure) {
            markIndependentFailure(
                    sourceAssetId, "CORRUPT_ASSET", "The source could not be staged for cover processing.");
            return;
        }
        try {
            var inserted = jdbc.sql("""
                    INSERT INTO cover_job (
                        id, work_id, source_asset_id, output_root_id, source_format,
                        source_sha256, source_byte_size, staged_input_name,
                        processor_version, state, created_at
                    ) VALUES (
                        :id, :workId, :sourceAssetId, :outputRootId, :format,
                        :sha256, :byteSize, :fileName,
                        :processorVersion, 'QUEUED', :createdAt
                    )
                    ON CONFLICT (source_asset_id, output_root_id, processor_version) DO NOTHING
                    """)
                    .param("id", staged.id())
                    .param("workId", target.get().workId())
                    .param("sourceAssetId", sourceAssetId)
                    .param("outputRootId", target.get().outputRootId())
                    .param("format", format.name())
                    .param("sha256", staged.sha256())
                    .param("byteSize", expectedSize)
                    .param("fileName", staged.fileName())
                    .param("processorVersion", PROCESSOR_VERSION)
                    .param("createdAt", timestamp(clock.instant()))
                    .update();
            if (inserted == 0) {
                deleteStaged(staged.fileName());
            }
        } catch (RuntimeException failure) {
            deleteStaged(staged.fileName());
            throw failure;
        }
    }

    @Override
    @Transactional
    public Optional<CoverWorkerClaim> claim() {
        failExhaustedLeases();
        var now = clock.instant();
        var id = jdbc.sql("""
                SELECT id
                FROM cover_job
                WHERE attempt_count < 3
                  AND (state = 'QUEUED' OR (state = 'RUNNING' AND lease_expires_at <= :now))
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """).param("now", timestamp(now)).query(UUID.class).optional();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        var token = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE cover_job
                SET state = 'RUNNING', attempt_count = attempt_count + 1,
                    lease_token = :token, lease_expires_at = :expiresAt,
                    started_at = COALESCE(started_at, :now),
                    error_code = NULL, safe_diagnostic = NULL
                WHERE id = :id
                RETURNING id, lease_token, source_byte_size AS byte_size,
                          source_sha256 AS sha256, source_format AS format
                """)
                .param("token", token)
                .param("expiresAt", timestamp(now.plus(properties.leaseDuration())))
                .param("now", timestamp(now))
                .param("id", id.get())
                .query(CoverWorkerClaim.class)
                .optional();
    }

    @Override
    public Path leasedInput(UUID jobId, UUID leaseToken) {
        var fileName = jdbc.sql("""
                SELECT staged_input_name
                FROM cover_job
                WHERE id = :id AND state = 'RUNNING' AND lease_token = :token
                  AND lease_expires_at > :now
                """)
                .param("id", jobId)
                .param("token", leaseToken)
                .param("now", timestamp(clock.instant()))
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("The cover worker lease is invalid or expired."));
        var root = stagingRoot();
        var input = root.resolve(fileName).normalize();
        if (!root.equals(input.getParent()) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The staged cover source is unavailable.");
        }
        return input;
    }

    @Override
    @Transactional
    public void complete(UUID jobId, UUID leaseToken, CoverWorkerResult result) {
        var job = requireLeasedJob(jobId, leaseToken);
        validateResult(result);
        if (result.state() != CoverWorkerResult.State.READY) {
            finish(jobId, result.state().name(), result.errorCode(), result.safeDiagnostic());
            deleteStaged(job.stagedInputName());
            return;
        }
        var output = decodeAndValidate(result);
        var outputHash = sha256(output.bytes());
        var extension = "image/png".equals(result.mediaType()) ? "png" : "jpg";
        var relativePath = "covers/" + outputHash.substring(0, 2) + "/" + outputHash + "." + extension;
        publish(job.outputRootId(), jobId, relativePath, output.bytes());
        jdbc.sql("""
                INSERT INTO cover_derivative (
                    id, job_id, work_id, source_asset_id, managed_root_id,
                    selection_kind, source_locator, input_sha256, output_sha256,
                    processor_name, processor_version, effective_limits,
                    width, height, media_type, byte_size, normalized_relative_path, created_at
                ) VALUES (
                    :id, :jobId, :workId, :sourceAssetId, :rootId,
                    :selectionKind, :sourceLocator, :inputSha256, :outputSha256,
                    :processorName, :processorVersion, CAST(:limits AS jsonb),
                    :width, :height, :mediaType, :byteSize, :path, :createdAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("jobId", jobId)
                .param("workId", job.workId())
                .param("sourceAssetId", job.sourceAssetId())
                .param("rootId", job.outputRootId())
                .param("selectionKind", result.selectionKind().name())
                .param("sourceLocator", result.sourceLocator())
                .param("inputSha256", result.inputSha256())
                .param("outputSha256", outputHash)
                .param("processorName", result.processorName())
                .param("processorVersion", result.processorVersion())
                .param(
                        "limits",
                        "{\"encodedBytes\":33554432,\"pixels\":8000000,\"decodedBytes\":134217728,\"deadlineSeconds\":30}")
                .param("width", output.width())
                .param("height", output.height())
                .param("mediaType", result.mediaType())
                .param("byteSize", output.bytes().length)
                .param("path", relativePath)
                .param("createdAt", timestamp(clock.instant()))
                .update();
        finish(jobId, "SUCCEEDED", null, null);
        deleteStaged(job.stagedInputName());
    }

    @Override
    public Optional<CoverContentLocation> findByWorkId(UUID workId) {
        var access = accessContext.current();
        return jdbc.sql("""
                WITH RECURSIVE candidates AS (
                    SELECT derivative.*,
                           CASE WHEN preference.source_asset_id = derivative.source_asset_id THEN 0
                                WHEN derivative.selection_kind = 'DECLARED_EMBEDDED' THEN 1
                                WHEN derivative.selection_kind = 'DOCX_THUMBNAIL' THEN 2
                                WHEN derivative.selection_kind = 'PDF_PAGE_ONE' THEN 3
                                ELSE 4 END AS selection_rank
                    FROM cover_derivative derivative
                    LEFT JOIN work_cover_preference preference ON preference.work_id = derivative.work_id
                    WHERE derivative.work_id = :workId
                ), lineage(candidate_id, asset_id) AS (
                    SELECT id, source_asset_id FROM candidates
                    UNION
                    SELECT lineage.candidate_id, source.source_asset_id
                    FROM lineage
                    JOIN asset_derivation_source source ON source.derived_asset_id = lineage.asset_id
                )
                SELECT candidate.work_id, candidate.managed_root_id AS root_id,
                       candidate.normalized_relative_path, candidate.media_type,
                       candidate.byte_size, candidate.output_sha256
                FROM candidates candidate
                WHERE :unrestricted
                   OR (NOT EXISTS (
                           SELECT 1 FROM user_root_deny denied
                           WHERE denied.user_id = :userId
                             AND denied.library_root_id = candidate.managed_root_id)
                       AND NOT EXISTS (
                           SELECT 1
                           FROM lineage
                           JOIN asset_location location ON location.asset_id = lineage.asset_id
                           JOIN user_root_deny denied
                             ON denied.library_root_id = location.library_root_id
                            AND denied.user_id = :userId
                           WHERE lineage.candidate_id = candidate.id))
                ORDER BY candidate.selection_rank, candidate.created_at, candidate.id
                LIMIT 1
                """)
                .param("workId", workId)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(CoverContentLocation.class)
                .optional();
    }

    @Override
    @Transactional
    public CoverPreference select(UUID workId, UUID sourceAssetId, String reason, long expectedVersion, UUID actorId) {
        if (!isAccessibleDerivative(workId, sourceAssetId)) {
            throw new CatalogCurationFailure(
                    CatalogCurationFailure.Code.WORK_NOT_FOUND, "The requested cover candidate does not exist.");
        }
        var current = jdbc.sql("SELECT version FROM work_cover_preference WHERE work_id = :workId FOR UPDATE")
                .param("workId", workId)
                .query(Long.class)
                .optional();
        if (current.isEmpty()) {
            if (expectedVersion != 0) {
                throw versionConflict();
            }
            jdbc.sql("""
                    INSERT INTO work_cover_preference (
                        work_id, source_asset_id, actor_user_id, reason, version, created_at, updated_at
                    ) VALUES (:workId, :sourceAssetId, :actorId, :reason, 1, :now, :now)
                    """)
                    .param("workId", workId)
                    .param("sourceAssetId", sourceAssetId)
                    .param("actorId", actorId)
                    .param("reason", reason)
                    .param("now", timestamp(clock.instant()))
                    .update();
            return new CoverPreference(workId, sourceAssetId, 1);
        }
        if (current.get() != expectedVersion) {
            throw versionConflict();
        }
        var next = Math.addExact(expectedVersion, 1);
        jdbc.sql("""
                UPDATE work_cover_preference
                SET source_asset_id = :sourceAssetId, actor_user_id = :actorId,
                    reason = :reason, version = :nextVersion, updated_at = :now
                WHERE work_id = :workId AND version = :expectedVersion
                """)
                .param("sourceAssetId", sourceAssetId)
                .param("actorId", actorId)
                .param("reason", reason)
                .param("nextVersion", next)
                .param("now", timestamp(clock.instant()))
                .param("workId", workId)
                .param("expectedVersion", expectedVersion)
                .update();
        return new CoverPreference(workId, sourceAssetId, next);
    }

    private boolean isAccessibleDerivative(UUID workId, UUID sourceAssetId) {
        var access = accessContext.current();
        return jdbc.sql("""
                WITH RECURSIVE candidate AS (
                    SELECT id, source_asset_id, managed_root_id
                    FROM cover_derivative
                    WHERE work_id = :workId AND source_asset_id = :sourceAssetId
                ), lineage(candidate_id, asset_id) AS (
                    SELECT id, source_asset_id FROM candidate
                    UNION
                    SELECT lineage.candidate_id, source.source_asset_id
                    FROM lineage
                    JOIN asset_derivation_source source ON source.derived_asset_id = lineage.asset_id
                )
                SELECT EXISTS (
                    SELECT 1 FROM candidate
                    WHERE :unrestricted
                       OR (NOT EXISTS (
                               SELECT 1 FROM user_root_deny denied
                               WHERE denied.user_id = :userId
                                 AND denied.library_root_id = candidate.managed_root_id)
                           AND NOT EXISTS (
                               SELECT 1
                               FROM lineage
                               JOIN asset_location location ON location.asset_id = lineage.asset_id
                               JOIN user_root_deny denied
                                 ON denied.library_root_id = location.library_root_id
                                AND denied.user_id = :userId
                               WHERE lineage.candidate_id = candidate.id)))
                """)
                .param("workId", workId)
                .param("sourceAssetId", sourceAssetId)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(Boolean.class)
                .single();
    }

    private static CatalogCurationFailure versionConflict() {
        return new CatalogCurationFailure(
                CatalogCurationFailure.Code.VERSION_CONFLICT, "The cover preference changed before this update.");
    }

    private Optional<JobTarget> findTarget(UUID sourceAssetId) {
        return jdbc.sql("""
                SELECT edition.work_id, root.id AS output_root_id
                FROM asset
                JOIN edition ON edition.id = asset.edition_id
                CROSS JOIN library_root root
                WHERE asset.id = :assetId
                  AND root.mode = 'MANAGED_OUTPUT'
                  AND root.default_for_covers
                """)
                .param("assetId", sourceAssetId)
                .query(JobTarget.class)
                .optional();
    }

    private boolean existingJob(UUID sourceAssetId, UUID outputRootId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM cover_job
                    WHERE source_asset_id = :assetId AND output_root_id = :rootId
                      AND processor_version = :version)
                """)
                .param("assetId", sourceAssetId)
                .param("rootId", outputRootId)
                .param("version", PROCESSOR_VERSION)
                .query(Boolean.class)
                .single();
    }

    private void markIndependentFailure(UUID sourceAssetId, String code, String diagnostic) {
        var target = findTarget(sourceAssetId);
        if (target.isEmpty() || existingJob(sourceAssetId, target.get().outputRootId())) {
            return;
        }
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO cover_job (
                    id, work_id, source_asset_id, output_root_id, source_format,
                    source_sha256, source_byte_size, staged_input_name,
                    processor_version, state, error_code, safe_diagnostic, created_at, completed_at
                ) SELECT :id, :workId, asset.id, :rootId, asset.format,
                         repeat('0', 64), GREATEST(asset.byte_size, 1), :fileName,
                         :version, 'FAILED_SAFE', :code, :diagnostic, :now, :now
                  FROM asset WHERE asset.id = :assetId AND asset.byte_size <= :maximum
                """)
                .param("id", id)
                .param("workId", target.get().workId())
                .param("rootId", target.get().outputRootId())
                .param("fileName", id + "." + sourceExtension(sourceAssetId))
                .param("version", PROCESSOR_VERSION)
                .param("code", code)
                .param("diagnostic", diagnostic)
                .param("now", timestamp(clock.instant()))
                .param("assetId", sourceAssetId)
                .param("maximum", MAXIMUM_SOURCE_BYTES)
                .update();
    }

    private String sourceExtension(UUID assetId) {
        return jdbc.sql("SELECT lower(format) FROM asset WHERE id = :id")
                .param("id", assetId)
                .query(String.class)
                .single();
    }

    private StagedSource stage(Path source, long expectedSize, Asset.Format format) throws IOException {
        var before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || Files.isSymbolicLink(source) || before.size() != expectedSize) {
            throw new IOException("The source changed before cover staging.");
        }
        var id = UUID.randomUUID();
        var fileName = id + "." + format.name().toLowerCase(Locale.ROOT);
        var destination = stagingRoot().resolve(fileName);
        var digest = newDigest();
        long copied = 0;
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                var output =
                        Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied = Math.addExact(copied, read);
                if (copied > expectedSize || copied > MAXIMUM_SOURCE_BYTES) {
                    throw new IOException("The source changed while cover staging.");
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
            throw new IOException("The source changed while cover staging.");
        }
        if (!destination.toFile().setReadOnly()) {
            Files.deleteIfExists(destination);
            throw new IOException("The staged cover source could not be made read-only.");
        }
        return new StagedSource(id, fileName, HexFormat.of().formatHex(digest.digest()));
    }

    private Path stagingRoot() {
        try {
            Files.createDirectories(properties.stagingDirectory());
            if (Files.isSymbolicLink(properties.stagingDirectory())) {
                throw new IOException("The cover staging directory must not be a symbolic link.");
            }
            return properties.stagingDirectory().toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("The cover staging directory is unavailable.", failure);
        }
    }

    private JobRow requireLeasedJob(UUID jobId, UUID token) {
        return jdbc.sql("""
                SELECT id, work_id, source_asset_id, output_root_id, staged_input_name
                FROM cover_job
                WHERE id = :id AND state = 'RUNNING' AND lease_token = :token
                  AND lease_expires_at > :now
                FOR UPDATE
                """)
                .param("id", jobId)
                .param("token", token)
                .param("now", timestamp(clock.instant()))
                .query(JobRow.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("The cover worker lease is invalid or expired."));
    }

    private void finish(UUID jobId, String state, String code, String diagnostic) {
        jdbc.sql("""
                UPDATE cover_job
                SET state = :state, lease_token = NULL, lease_expires_at = NULL,
                    error_code = :code, safe_diagnostic = :diagnostic, completed_at = :completedAt
                WHERE id = :id
                """)
                .param("state", state)
                .param("code", code)
                .param("diagnostic", diagnostic)
                .param("completedAt", timestamp(clock.instant()))
                .param("id", jobId)
                .update();
    }

    private void failExhaustedLeases() {
        jdbc.sql("""
                UPDATE cover_job
                SET state = 'FAILED_SAFE', lease_token = NULL, lease_expires_at = NULL,
                    error_code = 'PARSE_LIMIT_EXCEEDED',
                    safe_diagnostic = 'The cover worker exhausted its retry limit.',
                    completed_at = :now
                WHERE state = 'RUNNING' AND lease_expires_at <= :now AND attempt_count >= 3
                """).param("now", timestamp(clock.instant())).update();
    }

    private static void validateResult(CoverWorkerResult result) {
        if (result == null || result.state() == null) {
            throw new IllegalArgumentException("The cover worker result is invalid.");
        }
        selected(result.processorName(), 100);
        selected(result.processorVersion(), 100);
        if (!"yurlib-cover-worker".equals(result.processorName()) || !"1".equals(result.processorVersion())) {
            throw new IllegalArgumentException("The cover worker processor version is not accepted.");
        }
        if (result.state() != CoverWorkerResult.State.READY) {
            if (!ERROR_CODES.contains(result.errorCode())) {
                throw new IllegalArgumentException("The cover worker error code is invalid.");
            }
            selected(result.safeDiagnostic(), 1000);
            return;
        }
        if (result.selectionKind() == null || result.errorCode() != null || result.safeDiagnostic() != null) {
            throw new IllegalArgumentException("The ready cover result is invalid.");
        }
        selected(result.sourceLocator(), 1000);
        if (result.inputSha256() == null || !result.inputSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("The cover input digest is invalid.");
        }
        if (!Set.of("image/jpeg", "image/png").contains(result.mediaType())
                || result.outputBase64() == null
                || result.outputBase64().length() > ((MAXIMUM_OUTPUT_BYTES * 4L / 3L) + 8L)) {
            throw new IllegalArgumentException("The normalized cover encoding is invalid.");
        }
    }

    private static ValidatedImage decodeAndValidate(CoverWorkerResult result) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(result.outputBase64());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("The normalized cover is not valid base64.", failure);
        }
        if (bytes.length == 0 || bytes.length > MAXIMUM_OUTPUT_BYTES) {
            throw new IllegalArgumentException("The normalized cover size is invalid.");
        }
        try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("The normalized cover format is unsupported.");
            }
            var reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                var width = reader.getWidth(0);
                var height = reader.getHeight(0);
                if (width <= 0
                        || height <= 0
                        || width > 1600
                        || height > 2400
                        || Math.multiplyExact((long) width, height) > MAXIMUM_PIXELS
                        || !dimensionsMatch(result, width, height)) {
                    throw new IllegalArgumentException("The normalized cover dimensions are invalid.");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new IllegalArgumentException("The normalized cover could not be decoded.");
                }
                return new ValidatedImage(bytes, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("The normalized cover could not be validated.", failure);
        }
    }

    private static boolean dimensionsMatch(CoverWorkerResult result, int width, int height) {
        return result.width() != null
                && result.height() != null
                && result.width() == width
                && result.height() == height;
    }

    private void publish(UUID rootId, UUID jobId, String relativePath, byte[] bytes) {
        var root = roots.findById(rootId)
                .filter(candidate -> candidate.mode() == LibraryRoot.Mode.MANAGED_OUTPUT)
                .orElseThrow(() -> new IllegalStateException("The managed cover root is unavailable."));
        var verifiedRoot = verifier.verifyConfigured(root).path();
        var target = verifiedRoot.resolve(relativePath).normalize();
        var targetParent = java.util.Objects.requireNonNull(target.getParent(), "The cover target must have a parent.");
        var staging = verifiedRoot.resolve(".yurlib-staging/covers").normalize();
        var temporary = staging.resolve(jobId + ".part").normalize();
        try {
            if (!target.startsWith(verifiedRoot) || !temporary.startsWith(verifiedRoot)) {
                throw new IOException("The managed cover path escaped its root.");
            }
            Files.createDirectories(targetParent);
            Files.createDirectories(staging);
            rejectSymbolicComponents(verifiedRoot, targetParent);
            rejectSymbolicComponents(verifiedRoot, staging);
            try (var channel = FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.DSYNC)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(target) != bytes.length
                        || !sha256(Files.readAllBytes(target)).equals(sha256(bytes))) {
                    throw new IOException("An incompatible managed cover already exists.");
                }
                Files.deleteIfExists(temporary);
                return;
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("The managed root does not support atomic cover publication.", failure);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The unreferenced bounded staging file can be cleaned on the next maintenance pass.
            }
            throw new IllegalStateException("The normalized cover could not be published atomically.", failure);
        }
    }

    private static void rejectSymbolicComponents(Path root, Path candidate) throws IOException {
        var component = root;
        for (var name : root.relativize(candidate)) {
            component = component.resolve(name);
            if (Files.isSymbolicLink(component)) {
                throw new IOException("The managed cover path contains a symbolic link.");
            }
        }
    }

    private static void selected(String value, int maximumBytes) {
        if (value == null
                || value.isBlank()
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException("A cover worker selected value is invalid.");
        }
    }

    private void deleteStaged(String fileName) {
        try {
            var root = stagingRoot();
            var path = root.resolve(fileName).normalize();
            if (root.equals(path.getParent())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // Cleanup is best effort; bounded stale inputs can be retried by maintenance.
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", impossible);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record JobTarget(UUID workId, UUID outputRootId) {}

    private record StagedSource(UUID id, String fileName, String sha256) {}

    private record JobRow(UUID id, UUID workId, UUID sourceAssetId, UUID outputRootId, String stagedInputName) {}

    private record ValidatedImage(byte[] bytes, int width, int height) {}
}
