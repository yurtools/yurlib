CREATE TABLE library_root (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    mount_alias VARCHAR(63) NOT NULL CHECK (mount_alias ~ '^[a-z][a-z0-9-]*$'),
    relative_base_path VARCHAR(1024) NOT NULL,
    expected_identity_digest CHAR(64) NOT NULL
        CHECK (expected_identity_digest ~ '^[0-9a-f]{64}$'),
    mode VARCHAR(20) NOT NULL DEFAULT 'READ_ONLY' CHECK (mode = 'READ_ONLY'),
    availability VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (availability IN ('UNKNOWN', 'AVAILABLE', 'UNAVAILABLE', 'IDENTITY_MISMATCH')),
    last_successful_scan_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (mount_alias, relative_base_path),
    CHECK (relative_base_path !~ '(^|/)\.\.?(/|$)'),
    CHECK (relative_base_path !~ '^/'),
    CHECK (relative_base_path !~ '/$'),
    CHECK (relative_base_path NOT LIKE '%//%'),
    CHECK (position(chr(92) in relative_base_path) = 0)
);

CREATE TABLE scan_job (
    id UUID PRIMARY KEY,
    library_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE RESTRICT,
    state VARCHAR(30) NOT NULL
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'COMPLETED_WITH_FAILURES', 'FAILED', 'CANCELLED')),
    correlation_id VARCHAR(100) NOT NULL CHECK (btrim(correlation_id) <> ''),
    extraction_version VARCHAR(100) NOT NULL CHECK (btrim(extraction_version) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    discovered_count BIGINT NOT NULL DEFAULT 0 CHECK (discovered_count >= 0),
    processed_count BIGINT NOT NULL DEFAULT 0 CHECK (processed_count >= 0),
    skipped_count BIGINT NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
    failed_count BIGINT NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    completion_coverage BOOLEAN NOT NULL DEFAULT FALSE,
    error_summary VARCHAR(1000),
    CHECK (processed_count + skipped_count + failed_count <= discovered_count)
);

CREATE UNIQUE INDEX one_active_scan_per_root
    ON scan_job (library_root_id)
    WHERE state IN ('QUEUED', 'RUNNING');

CREATE TABLE file_outcome (
    scan_job_id UUID NOT NULL REFERENCES scan_job(id) ON DELETE CASCADE,
    normalized_relative_path VARCHAR(2000) NOT NULL,
    state VARCHAR(20) NOT NULL
        CHECK (state IN ('DISCOVERED', 'PROCESSED', 'SKIPPED', 'FAILED', 'DEFERRED')),
    error_code VARCHAR(100),
    safe_diagnostic VARCHAR(1000),
    attempt_count INTEGER NOT NULL DEFAULT 1 CHECK (attempt_count > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scan_job_id, normalized_relative_path),
    CHECK (normalized_relative_path <> ''),
    CHECK (normalized_relative_path !~ '(^|/)\.\.?(/|$)'),
    CHECK (normalized_relative_path !~ '^/'),
    CHECK (normalized_relative_path !~ '/$'),
    CHECK (normalized_relative_path NOT LIKE '%//%'),
    CHECK (position(chr(92) in normalized_relative_path) = 0)
);

CREATE TABLE work (
    id UUID PRIMARY KEY,
    provisional_title VARCHAR(1000) NOT NULL CHECK (btrim(provisional_title) <> ''),
    resolution_state VARCHAR(20) NOT NULL DEFAULT 'PROVISIONAL'
        CHECK (resolution_state IN ('PROVISIONAL', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE edition (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES work(id) ON DELETE RESTRICT,
    observed_language VARCHAR(35),
    identifiers JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(identifiers) = 'object'),
    resolution_state VARCHAR(20) NOT NULL DEFAULT 'PROVISIONAL'
        CHECK (resolution_state IN ('PROVISIONAL', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE asset (
    id UUID PRIMARY KEY,
    edition_id UUID NOT NULL REFERENCES edition(id) ON DELETE RESTRICT,
    format VARCHAR(10) NOT NULL CHECK (format IN ('EPUB', 'FB2', 'MOBI')),
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    derivation VARCHAR(20) NOT NULL DEFAULT 'ORIGINAL' CHECK (derivation = 'ORIGINAL'),
    content_hash CHAR(64) CHECK (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$'),
    extraction_version VARCHAR(100) NOT NULL CHECK (btrim(extraction_version) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE asset_location (
    id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    library_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE RESTRICT,
    normalized_relative_path VARCHAR(2000) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    modified_at TIMESTAMPTZ NOT NULL,
    file_key VARCHAR(1000),
    availability VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (availability IN ('AVAILABLE', 'MISSING', 'UNAVAILABLE')),
    last_seen_scan_id UUID REFERENCES scan_job(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (library_root_id, normalized_relative_path),
    CHECK (normalized_relative_path <> ''),
    CHECK (normalized_relative_path !~ '(^|/)\.\.?(/|$)'),
    CHECK (normalized_relative_path !~ '^/'),
    CHECK (normalized_relative_path !~ '/$'),
    CHECK (normalized_relative_path NOT LIKE '%//%'),
    CHECK (position(chr(92) in normalized_relative_path) = 0)
);

CREATE TABLE metadata_observation (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('WORK', 'EDITION', 'ASSET')),
    field_name VARCHAR(100) NOT NULL CHECK (btrim(field_name) <> ''),
    observed_value TEXT NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'FILE' CHECK (source = 'FILE'),
    parser_name VARCHAR(100) NOT NULL CHECK (btrim(parser_name) <> ''),
    parser_version VARCHAR(100) NOT NULL CHECK (btrim(parser_version) <> ''),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX scan_job_root_created_idx ON scan_job (library_root_id, created_at DESC);
CREATE INDEX file_outcome_state_idx ON file_outcome (scan_job_id, state);
CREATE INDEX edition_work_idx ON edition (work_id);
CREATE INDEX asset_edition_idx ON asset (edition_id);
CREATE INDEX asset_location_asset_idx ON asset_location (asset_id);
CREATE INDEX metadata_observation_subject_idx
    ON metadata_observation (subject_type, subject_id, field_name);

UPDATE yurlib_metadata
SET metadata_value = '2'
WHERE metadata_key = 'schema_version';
