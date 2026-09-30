ALTER TABLE asset
    ADD COLUMN metadata_state VARCHAR(20) NOT NULL DEFAULT 'READY'
        CHECK (metadata_state IN ('PENDING', 'READY', 'FAILED_SAFE'));

CREATE TABLE pdf_metadata_job (
    id UUID PRIMARY KEY,
    library_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE RESTRICT,
    scan_job_id UUID NOT NULL REFERENCES scan_job(id) ON DELETE RESTRICT,
    normalized_relative_path VARCHAR(2000) NOT NULL,
    file_key VARCHAR(1000),
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    modified_at TIMESTAMPTZ NOT NULL,
    extraction_version VARCHAR(100) NOT NULL CHECK (btrim(extraction_version) <> ''),
    staged_file_name VARCHAR(100) NOT NULL UNIQUE CHECK (staged_file_name ~ '^[0-9a-f-]{36}\.pdf$'),
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    state VARCHAR(20) NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED_SAFE')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0 AND attempt_count <= 3),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    safe_diagnostic VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (library_root_id, normalized_relative_path, byte_size, modified_at, extraction_version),
    CHECK (normalized_relative_path <> ''),
    CHECK (normalized_relative_path !~ '(^|/)\.\.?(/|$)'),
    CHECK (normalized_relative_path !~ '^/'),
    CHECK (position(chr(92) in normalized_relative_path) = 0),
    CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);

CREATE INDEX pdf_metadata_job_claim_idx
    ON pdf_metadata_job (created_at, id)
    WHERE state IN ('QUEUED', 'RUNNING');

UPDATE yurlib_metadata
SET metadata_value = '6'
WHERE metadata_key = 'schema_version';
