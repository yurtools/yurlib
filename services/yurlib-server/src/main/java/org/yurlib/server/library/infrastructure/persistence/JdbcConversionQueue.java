package org.yurlib.server.library.infrastructure.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yurlib.server.library.application.ConversionFailure;
import org.yurlib.server.library.application.ConversionJob;
import org.yurlib.server.library.application.ConversionRoute;
import org.yurlib.server.library.application.ConversionUseCases;
import org.yurlib.server.library.application.ConversionWorkerClaim;
import org.yurlib.server.library.application.ConversionWorkerJobService;
import org.yurlib.server.library.application.ConversionWorkerResult;
import org.yurlib.server.library.application.LibraryAccessContext;
import org.yurlib.server.library.application.LibraryRootStore;
import org.yurlib.server.library.application.OriginalAssetContentUseCases;
import org.yurlib.server.library.domain.Asset;
import org.yurlib.server.library.domain.LibraryRoot;
import org.yurlib.server.library.infrastructure.config.ConversionWorkerProperties;
import org.yurlib.server.library.infrastructure.conversion.EpubConversionValidator;
import org.yurlib.server.library.infrastructure.filesystem.FilesystemRootVerifier;

@Component
public class JdbcConversionQueue implements ConversionUseCases, ConversionWorkerJobService {

    private static final long MAXIMUM_SOURCE_BYTES = 512L * 1024 * 1024;
    private static final long MAXIMUM_OUTPUT_BYTES = 1024L * 1024 * 1024;
    private static final long DEADLINE_SECONDS = 600;
    private static final int MAXIMUM_ATTEMPTS = 3;
    private static final String CONVERTER_NAME = "calibre";
    private static final String CONVERTER_VERSION = "9.15.0";
    private static final Set<String> ERROR_CODES = Set.of(
            "CONVERSION_REJECTED", "CONVERSION_TIMEOUT", "CONVERSION_CRASHED", "OUTPUT_INVALID", "RESOURCE_LIMIT");

    private final JdbcClient jdbc;
    private final OriginalAssetContentUseCases content;
    private final LibraryRootStore roots;
    private final FilesystemRootVerifier verifier;
    private final LibraryAccessContext accessContext;
    private final ConversionWorkerProperties properties;
    private final EpubConversionValidator validator;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring owns the infrastructure collaborators.")
    public JdbcConversionQueue(
            JdbcClient jdbc,
            OriginalAssetContentUseCases content,
            LibraryRootStore roots,
            FilesystemRootVerifier verifier,
            LibraryAccessContext accessContext,
            ConversionWorkerProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.content = content;
        this.roots = roots;
        this.verifier = verifier;
        this.accessContext = accessContext;
        this.properties = properties;
        this.validator = new EpubConversionValidator();
        this.clock = clock;
    }

    @Override
    @Transactional
    public ConversionJob request(UUID sourceAssetId, ConversionRoute route) {
        var opened = content.open(sourceAssetId);
        if (route == null || opened.format() != route.sourceFormat()) {
            closeQuietly(opened.content());
            throw new ConversionFailure(
                    ConversionFailure.Code.ROUTE_NOT_SUPPORTED, "The requested conversion route is not supported.");
        }
        if (opened.byteSize() <= 0 || opened.byteSize() > MAXIMUM_SOURCE_BYTES) {
            closeQuietly(opened.content());
            throw new ConversionFailure(
                    ConversionFailure.Code.CONVERSION_LIMIT_EXCEEDED,
                    "The source exceeds the conversion staging limit.");
        }
        var outputRootId = target(sourceAssetId)
                .orElseThrow(() -> new ConversionFailure(
                        ConversionFailure.Code.MANAGED_ROOT_UNAVAILABLE,
                        "An accessible default managed conversion root is required."));
        var staged = stage(opened.content(), opened.byteSize(), opened.format());
        var routeKey = sha256(staged.sha256() + "|" + route.version() + "|" + route.effectiveSettings());
        var existing = findByRouteKey(routeKey);
        if (existing.isPresent()) {
            deleteStaged(staged.inputName());
            return existing.get();
        }
        var id = UUID.randomUUID();
        var keepStagedInput = false;
        try {
            var inserted = jdbc.sql("""
                    INSERT INTO conversion_job (
                        id, requested_by, source_asset_id, output_root_id,
                        source_format, target_format, route, route_version, route_key,
                        effective_settings, source_sha256, source_byte_size,
                        staged_input_name, state, created_at
                    ) VALUES (
                        :id, :actorId, :sourceAssetId, :outputRootId,
                        :sourceFormat, 'EPUB', :route, :routeVersion, :routeKey,
                        CAST(:settings AS jsonb), :sourceSha256, :sourceByteSize,
                        :inputName, 'QUEUED', :createdAt
                    ) ON CONFLICT (route_key) DO NOTHING
                    """)
                    .param("id", id)
                    .param(
                            "actorId",
                            accessContext.current().unrestricted()
                                    ? null
                                    : accessContext.current().userId(),
                            java.sql.Types.OTHER)
                    .param("sourceAssetId", sourceAssetId)
                    .param("outputRootId", outputRootId)
                    .param("sourceFormat", opened.format().name())
                    .param("route", route.name())
                    .param("routeVersion", route.version())
                    .param("routeKey", routeKey)
                    .param("settings", route.effectiveSettings())
                    .param("sourceSha256", staged.sha256())
                    .param("sourceByteSize", opened.byteSize())
                    .param("inputName", staged.inputName())
                    .param("createdAt", timestamp(clock.instant()))
                    .update();
            if (inserted == 0) {
                return findByRouteKey(routeKey)
                        .orElseThrow(() -> new ConversionFailure(
                                ConversionFailure.Code.MANAGED_ROOT_UNAVAILABLE,
                                "The matching conversion is outside the accessible library roots."));
            }
            var job = requireVisible(id);
            keepStagedInput = true;
            return job;
        } finally {
            if (!keepStagedInput) {
                deleteStaged(staged.inputName());
            }
        }
    }

    @Override
    public ConversionJob find(UUID jobId) {
        return requireVisible(jobId);
    }

    @Override
    @Transactional
    public ConversionJob cancel(UUID jobId, long expectedVersion) {
        var job = requireVisible(jobId);
        if (job.version() != expectedVersion) {
            throw new ConversionFailure(
                    ConversionFailure.Code.VERSION_CONFLICT, "The conversion job changed before cancellation.");
        }
        var now = clock.instant();
        var updated = jdbc.sql("""
                UPDATE conversion_job
                SET state = CASE WHEN state = 'QUEUED' THEN 'CANCELLED' ELSE state END,
                    cancellation_requested = TRUE,
                    completed_at = CASE WHEN state = 'QUEUED' THEN :now ELSE completed_at END,
                    version = version + 1
                WHERE id = :id AND version = :version AND state IN ('QUEUED', 'RUNNING')
                """)
                .param("now", timestamp(now))
                .param("id", jobId)
                .param("version", expectedVersion)
                .update();
        if (updated == 0 && job.state() != ConversionJob.State.CANCELLED) {
            throw new ConversionFailure(
                    ConversionFailure.Code.VERSION_CONFLICT, "The conversion job changed before cancellation.");
        }
        if (job.state() == ConversionJob.State.QUEUED) {
            deleteInputFor(jobId);
        }
        return requireVisible(jobId);
    }

    @Override
    @Transactional
    public Optional<ConversionWorkerClaim> claim() {
        failExhaustedLeases();
        var now = clock.instant();
        var id = jdbc.sql("""
                SELECT id FROM conversion_job
                WHERE attempt_count < :maximumAttempts AND NOT cancellation_requested
                  AND (state = 'QUEUED' OR (state = 'RUNNING' AND lease_expires_at <= :now))
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED LIMIT 1
                """)
                .param("maximumAttempts", MAXIMUM_ATTEMPTS)
                .param("now", timestamp(now))
                .query(UUID.class)
                .optional();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        jdbc.sql("SELECT staged_output_name FROM conversion_job WHERE id = :id")
                .param("id", id.get())
                .query(String.class)
                .optional()
                .ifPresent(this::deleteStaged);
        var token = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE conversion_job
                SET state = 'RUNNING', attempt_count = attempt_count + 1,
                    lease_token = :token, lease_expires_at = :expiresAt, heartbeat_at = :now,
                    started_at = COALESCE(started_at, :now), error_code = NULL,
                    safe_diagnostic = NULL, staged_output_name = NULL,
                    uploaded_output_sha256 = NULL, uploaded_output_byte_size = NULL,
                    version = version + 1
                WHERE id = :id
                RETURNING id, lease_token, source_byte_size AS byte_size,
                          source_sha256 AS sha256, source_format, route,
                          route_version, effective_settings::text AS effective_settings
                """)
                .param("token", token)
                .param("expiresAt", timestamp(now.plus(properties.leaseDuration())))
                .param("now", timestamp(now))
                .param("id", id.get())
                .query((result, row) -> new ConversionWorkerClaim(
                        result.getObject("id", UUID.class),
                        result.getObject("lease_token", UUID.class),
                        result.getLong("byte_size"),
                        result.getString("sha256"),
                        Asset.Format.valueOf(result.getString("source_format")),
                        ConversionRoute.valueOf(result.getString("route")),
                        result.getString("route_version"),
                        result.getString("effective_settings"),
                        DEADLINE_SECONDS))
                .optional();
    }

    @Override
    @Transactional
    public boolean heartbeat(UUID jobId, UUID leaseToken) {
        var now = clock.instant();
        return jdbc.sql("""
                UPDATE conversion_job
                SET heartbeat_at = :now, lease_expires_at = :expiresAt
                WHERE id = :id AND state = 'RUNNING' AND lease_token = :token
                  AND lease_expires_at > :now
                RETURNING cancellation_requested
                """)
                .param("now", timestamp(now))
                .param("expiresAt", timestamp(now.plus(properties.leaseDuration())))
                .param("id", jobId)
                .param("token", leaseToken)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("The conversion worker lease is invalid or expired."));
    }

    @Override
    public Path leasedInput(UUID jobId, UUID leaseToken) {
        return leasedPath(jobId, leaseToken, "staged_input_name");
    }

    @Override
    @Transactional
    public void upload(UUID jobId, UUID leaseToken, long byteSize, String expectedSha256, InputStream uploadedContent) {
        if (byteSize <= 0
                || byteSize > MAXIMUM_OUTPUT_BYTES
                || expectedSha256 == null
                || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("The conversion output declaration is invalid.");
        }
        requireLease(jobId, leaseToken);
        var fileName = jobId + ".epub";
        var target = stagingRoot().resolve(fileName).normalize();
        if (!stagingRoot().equals(target.getParent())) {
            throw new IllegalArgumentException("The conversion output path is invalid.");
        }
        var digest = newDigest();
        long copied = 0;
        try (uploadedContent;
                var output = Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = uploadedContent.read(buffer)) >= 0) {
                copied = Math.addExact(copied, read);
                if (copied > byteSize || copied > MAXIMUM_OUTPUT_BYTES) {
                    throw new IOException("The conversion output exceeded its declared size.");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException failure) {
            deletePath(target);
            throw new IllegalArgumentException("The conversion output upload failed safely.", failure);
        }
        var actual = HexFormat.of().formatHex(digest.digest());
        if (copied != byteSize || !MessageDigest.isEqual(actual.getBytes(), expectedSha256.getBytes())) {
            deletePath(target);
            throw new IllegalArgumentException("The conversion output integrity check failed.");
        }
        jdbc.sql("""
                UPDATE conversion_job
                SET staged_output_name = :fileName, uploaded_output_sha256 = :sha256,
                    uploaded_output_byte_size = :byteSize
                WHERE id = :id AND state = 'RUNNING' AND lease_token = :token
                """)
                .param("fileName", fileName)
                .param("sha256", actual)
                .param("byteSize", copied)
                .param("id", jobId)
                .param("token", leaseToken)
                .update();
    }

    @Override
    @Transactional
    public void complete(UUID jobId, UUID leaseToken, ConversionWorkerResult result) {
        var job = requireLease(jobId, leaseToken);
        validateResult(result);
        if (result.state() != ConversionWorkerResult.State.SUCCEEDED || job.cancellationRequested()) {
            finish(
                    jobId,
                    job.cancellationRequested() || result.state() == ConversionWorkerResult.State.CANCELLED
                            ? "CANCELLED"
                            : "FAILED_SAFE",
                    result.errorCode(),
                    result.safeDiagnostic(),
                    null);
            cleanup(job);
            return;
        }
        if (!result.outputSha256().equals(job.outputSha256())
                || !java.util.Objects.equals(result.outputByteSize(), job.outputByteSize())) {
            throw new IllegalArgumentException("The conversion result does not match the uploaded output.");
        }
        var output = stagedOutput(job);
        try {
            if (Files.size(output) != job.outputByteSize() || !hash(output).equals(job.outputSha256())) {
                throw new IOException("The staged conversion output changed before publication.");
            }
            validator.validate(output);
            var relativePath =
                    "conversions/" + result.outputSha256().substring(0, 2) + "/" + result.outputSha256() + ".epub";
            var published = publish(job.outputRootId(), jobId, relativePath, output);
            var catalogPersisted = false;
            try {
                var derivedId = persistDerivedAsset(job, relativePath, published.path(), result.outputSha256());
                finish(jobId, "SUCCEEDED", null, null, derivedId);
                catalogPersisted = true;
            } finally {
                if (!catalogPersisted && published.created()) {
                    deletePath(published.path());
                }
            }
        } catch (IOException failure) {
            finish(jobId, "FAILED_SAFE", "OUTPUT_INVALID", "The converted EPUB failed structural validation.", null);
        } finally {
            cleanup(job);
        }
    }

    private Optional<UUID> target(UUID sourceAssetId) {
        var access = accessContext.current();
        return jdbc.sql("""
                SELECT root.id
                FROM asset
                CROSS JOIN library_root root
                WHERE asset.id = :assetId AND asset.derivation = 'ORIGINAL'
                  AND root.mode = 'MANAGED_OUTPUT' AND root.default_for_conversions
                  AND root.availability = 'AVAILABLE'
                  AND (:unrestricted OR NOT EXISTS (
                      SELECT 1 FROM user_root_deny denied
                      WHERE denied.user_id = :userId AND denied.library_root_id = root.id))
                """)
                .param("assetId", sourceAssetId)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(UUID.class)
                .optional();
    }

    private StagedSource stage(InputStream input, long expectedSize, Asset.Format format) {
        var id = UUID.randomUUID();
        var fileName = id + "." + format.name().toLowerCase(Locale.ROOT);
        var target = stagingRoot().resolve(fileName);
        var digest = newDigest();
        long copied = 0;
        try (input;
                var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied = Math.addExact(copied, read);
                if (copied > expectedSize || copied > MAXIMUM_SOURCE_BYTES) {
                    throw new IOException("The conversion source changed while staging.");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            if (copied != expectedSize || !target.toFile().setReadOnly()) {
                throw new IOException("The conversion source could not be staged safely.");
            }
            return new StagedSource(fileName, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException failure) {
            deletePath(target);
            throw new ConversionFailure(
                    ConversionFailure.Code.CONVERSION_FAILED,
                    "The source could not be staged for conversion.",
                    failure);
        }
    }

    private ConversionJob requireVisible(UUID id) {
        var access = accessContext.current();
        return jdbc.sql("""
                SELECT job.* FROM conversion_job job
                WHERE job.id = :id
                  AND (:unrestricted OR (
                      NOT EXISTS (SELECT 1 FROM user_root_deny denied
                          WHERE denied.user_id = :userId AND denied.library_root_id = job.output_root_id)
                      AND NOT EXISTS (
                          SELECT 1 FROM asset_location location
                          JOIN user_root_deny denied ON denied.library_root_id = location.library_root_id
                          WHERE location.asset_id = job.source_asset_id AND denied.user_id = :userId)))
                """)
                .param("id", id)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(JdbcConversionQueue::mapJob)
                .optional()
                .orElseThrow(() -> new ConversionFailure(
                        ConversionFailure.Code.JOB_NOT_FOUND, "The requested conversion job does not exist."));
    }

    private Optional<ConversionJob> findByRouteKey(String routeKey) {
        var access = accessContext.current();
        return jdbc.sql("""
                SELECT job.* FROM conversion_job job
                WHERE job.route_key = :routeKey
                  AND (:unrestricted OR (
                      NOT EXISTS (SELECT 1 FROM user_root_deny denied
                          WHERE denied.user_id = :userId AND denied.library_root_id = job.output_root_id)
                      AND NOT EXISTS (
                          SELECT 1 FROM asset_location location
                          JOIN user_root_deny denied ON denied.library_root_id = location.library_root_id
                          WHERE location.asset_id = job.source_asset_id AND denied.user_id = :userId)))
                """)
                .param("routeKey", routeKey)
                .param("unrestricted", access.unrestricted())
                .param("userId", access.userId())
                .query(JdbcConversionQueue::mapJob)
                .optional();
    }

    private Path leasedPath(UUID id, UUID token, String column) {
        if (!"staged_input_name".equals(column)) {
            throw new IllegalArgumentException("The staged conversion column is invalid.");
        }
        var name = jdbc.sql("""
                SELECT staged_input_name FROM conversion_job
                WHERE id = :id AND state = 'RUNNING' AND lease_token = :token
                  AND lease_expires_at > :now
                """)
                .param("id", id)
                .param("token", token)
                .param("now", timestamp(clock.instant()))
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("The conversion worker lease is invalid or expired."));
        var root = stagingRoot();
        var path = root.resolve(name).normalize();
        if (!root.equals(path.getParent()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The staged conversion source is unavailable.");
        }
        return path;
    }

    private LeasedJob requireLease(UUID id, UUID token) {
        return jdbc.sql("""
                SELECT id, source_asset_id, output_root_id,
                       staged_input_name AS input_name,
                       staged_output_name AS output_name,
                       uploaded_output_sha256 AS output_sha256,
                       uploaded_output_byte_size AS output_byte_size,
                       cancellation_requested
                FROM conversion_job
                WHERE id = :id AND state = 'RUNNING' AND lease_token = :token
                  AND lease_expires_at > :now FOR UPDATE
                """)
                .param("id", id)
                .param("token", token)
                .param("now", timestamp(clock.instant()))
                .query(LeasedJob.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("The conversion worker lease is invalid or expired."));
    }

    private Path stagedOutput(LeasedJob job) {
        if (job.outputName() == null) {
            throw new IllegalArgumentException("The conversion output was not uploaded.");
        }
        var root = stagingRoot();
        var output = root.resolve(job.outputName()).normalize();
        if (!root.equals(output.getParent()) || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The staged conversion output is unavailable.");
        }
        return output;
    }

    private Published publish(UUID rootId, UUID jobId, String relativePath, Path source) throws IOException {
        var root = roots.findById(rootId)
                .filter(candidate -> candidate.mode() == LibraryRoot.Mode.MANAGED_OUTPUT)
                .orElseThrow(() -> new IOException("The managed conversion root is unavailable."));
        var verifiedRoot = verifier.verifyConfigured(root).path();
        var target = verifiedRoot.resolve(relativePath).normalize();
        var parent = java.util.Objects.requireNonNull(target.getParent());
        var staging = verifiedRoot.resolve(".yurlib-staging/conversions").normalize();
        var temporary = staging.resolve(jobId + ".part").normalize();
        if (!target.startsWith(verifiedRoot) || !temporary.startsWith(verifiedRoot)) {
            throw new IOException("The managed conversion path escaped its root.");
        }
        Files.createDirectories(parent);
        Files.createDirectories(staging);
        rejectSymlinks(verifiedRoot, parent);
        rejectSymlinks(verifiedRoot, staging);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !hash(target).equals(hash(source))) {
                throw new IOException("The existing conversion target does not match the validated output.");
            }
            return new Published(target, false);
        }
        try (var input = Files.newInputStream(source);
                var channel = FileChannel.open(
                        temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.DSYNC)) {
            input.transferTo(java.nio.channels.Channels.newOutputStream(channel));
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            deletePath(temporary);
            throw new IOException("The managed root does not support atomic conversion publication.", failure);
        }
        return new Published(target, true);
    }

    private UUID persistDerivedAsset(LeasedJob job, String relativePath, Path published, String hash)
            throws IOException {
        var editionId = jdbc.sql("SELECT edition_id FROM asset WHERE id = :id")
                .param("id", job.sourceAssetId())
                .query(UUID.class)
                .single();
        var assetId = UUID.randomUUID();
        var attributes = Files.readAttributes(published, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        jdbc.sql("""
                INSERT INTO asset (id, edition_id, format, byte_size, derivation, content_hash, extraction_version)
                VALUES (:id, :editionId, 'EPUB', :byteSize, 'DERIVED', :hash, 'calibre-9.15.0')
                """)
                .param("id", assetId)
                .param("editionId", editionId)
                .param("byteSize", attributes.size())
                .param("hash", hash)
                .update();
        jdbc.sql("""
                INSERT INTO asset_location (
                    id, asset_id, library_root_id, normalized_relative_path,
                    byte_size, modified_at, file_key, availability
                ) VALUES (:id, :assetId, :rootId, :path, :byteSize, :modifiedAt, :fileKey, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .param("rootId", job.outputRootId())
                .param("path", relativePath)
                .param("byteSize", attributes.size())
                .param(
                        "modifiedAt",
                        Timestamp.from(attributes.lastModifiedTime().toInstant()))
                .param(
                        "fileKey",
                        attributes.fileKey() == null
                                ? null
                                : attributes.fileKey().toString())
                .update();
        jdbc.sql("""
                INSERT INTO asset_derivation_source (derived_asset_id, source_asset_id, purpose)
                VALUES (:derivedId, :sourceId, 'FORMAT_CONVERSION')
                """)
                .param("derivedId", assetId)
                .param("sourceId", job.sourceAssetId())
                .update();
        return assetId;
    }

    private void finish(UUID id, String state, String code, String diagnostic, UUID derivedId) {
        jdbc.sql("""
                UPDATE conversion_job
                SET state = :state, derived_asset_id = :derivedId,
                    lease_token = NULL, lease_expires_at = NULL,
                    error_code = :code, safe_diagnostic = :diagnostic,
                    completed_at = :completedAt, version = version + 1
                WHERE id = :id
                """)
                .param("state", state)
                .param("derivedId", derivedId)
                .param("code", code)
                .param("diagnostic", diagnostic)
                .param("completedAt", timestamp(clock.instant()))
                .param("id", id)
                .update();
    }

    private void failExhaustedLeases() {
        var now = clock.instant();
        var exhausted = jdbc.sql("""
                SELECT staged_input_name AS input_name, staged_output_name AS output_name
                FROM conversion_job
                WHERE state = 'RUNNING' AND lease_expires_at <= :now AND attempt_count >= :maximumAttempts
                """)
                .param("now", timestamp(now))
                .param("maximumAttempts", MAXIMUM_ATTEMPTS)
                .query(StagedFiles.class)
                .list();
        jdbc.sql("""
                UPDATE conversion_job
                SET state = CASE WHEN cancellation_requested THEN 'CANCELLED' ELSE 'FAILED_SAFE' END,
                    lease_token = NULL, lease_expires_at = NULL,
                    error_code = CASE WHEN cancellation_requested THEN NULL ELSE 'CONVERSION_CRASHED' END,
                    safe_diagnostic = CASE WHEN cancellation_requested THEN NULL
                        ELSE 'The conversion worker exhausted its retry limit.' END,
                    completed_at = :now, version = version + 1
                WHERE state = 'RUNNING' AND lease_expires_at <= :now AND attempt_count >= :maximumAttempts
                """)
                .param("now", timestamp(now))
                .param("maximumAttempts", MAXIMUM_ATTEMPTS)
                .update();
        exhausted.forEach(files -> {
            deleteStaged(files.inputName());
            if (files.outputName() != null) {
                deleteStaged(files.outputName());
            }
        });
    }

    private void cleanup(LeasedJob job) {
        deleteStaged(job.inputName());
        if (job.outputName() != null) {
            deleteStaged(job.outputName());
        }
    }

    private void deleteInputFor(UUID jobId) {
        jdbc.sql("SELECT staged_input_name FROM conversion_job WHERE id = :id")
                .param("id", jobId)
                .query(String.class)
                .optional()
                .ifPresent(this::deleteStaged);
    }

    private Path stagingRoot() {
        try {
            Files.createDirectories(properties.stagingDirectory());
            if (Files.isSymbolicLink(properties.stagingDirectory())) {
                throw new IOException("The conversion staging directory must not be a symbolic link.");
            }
            return properties.stagingDirectory().toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("The conversion staging directory is unavailable.", failure);
        }
    }

    private void deleteStaged(String name) {
        var root = stagingRoot();
        var path = root.resolve(name).normalize();
        if (root.equals(path.getParent())) {
            deletePath(path);
        }
    }

    private static void validateResult(ConversionWorkerResult result) {
        if (result == null || result.state() == null) {
            throw new IllegalArgumentException("The conversion worker result is invalid.");
        }
        if (!CONVERTER_NAME.equals(result.converterName()) || !CONVERTER_VERSION.equals(result.converterVersion())) {
            throw new IllegalArgumentException("The conversion worker version is not accepted.");
        }
        if (result.state() == ConversionWorkerResult.State.SUCCEEDED) {
            if (result.outputSha256() == null
                    || !result.outputSha256().matches("[0-9a-f]{64}")
                    || result.outputByteSize() == null
                    || result.outputByteSize() <= 0
                    || result.outputByteSize() > MAXIMUM_OUTPUT_BYTES
                    || result.errorCode() != null
                    || result.safeDiagnostic() != null) {
                throw new IllegalArgumentException("The successful conversion result is invalid.");
            }
        } else if (result.state() == ConversionWorkerResult.State.FAILED_SAFE) {
            if (!ERROR_CODES.contains(result.errorCode())
                    || result.safeDiagnostic() == null
                    || result.safeDiagnostic().isBlank()
                    || result.safeDiagnostic().length() > 1000) {
                throw new IllegalArgumentException("The failed conversion result is invalid.");
            }
        }
    }

    private static ConversionJob mapJob(ResultSet result, int ignoredRowNumber) throws SQLException {
        return new ConversionJob(
                result.getObject("id", UUID.class),
                result.getObject("source_asset_id", UUID.class),
                result.getObject("derived_asset_id", UUID.class),
                ConversionRoute.valueOf(result.getString("route")),
                ConversionJob.State.valueOf(result.getString("state")),
                result.getInt("attempt_count"),
                result.getBoolean("cancellation_requested"),
                result.getString("error_code"),
                result.getString("safe_diagnostic"),
                instant(result, "created_at"),
                instant(result, "started_at"),
                instant(result, "completed_at"),
                result.getLong("version"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void rejectSymlinks(Path root, Path candidate) throws IOException {
        var component = root;
        for (var name : root.relativize(candidate)) {
            component = component.resolve(name);
            if (Files.isSymbolicLink(component)) {
                throw new IOException("The managed conversion path contains a symbolic link.");
            }
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The request is already being rejected.
        }
    }

    private static void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Bounded staging cleanup may retry later.
        }
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(newDigest().digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String hash(Path path) throws IOException {
        var digest = newDigest();
        try (var input = Files.newInputStream(path, StandardOpenOption.READ)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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

    private record StagedSource(String inputName, String sha256) {}

    private record StagedFiles(String inputName, String outputName) {}

    private record LeasedJob(
            UUID id,
            UUID sourceAssetId,
            UUID outputRootId,
            String inputName,
            String outputName,
            String outputSha256,
            Long outputByteSize,
            boolean cancellationRequested) {}

    private record Published(Path path, boolean created) {}
}
