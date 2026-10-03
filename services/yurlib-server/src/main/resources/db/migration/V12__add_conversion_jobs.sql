ALTER TABLE library_root
    ADD COLUMN default_for_conversions BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT library_root_conversion_default_check
        CHECK (NOT default_for_conversions OR mode = 'MANAGED_OUTPUT');

UPDATE library_root
SET default_for_conversions = TRUE
WHERE default_for_covers;

CREATE UNIQUE INDEX library_root_one_conversion_default_idx
    ON library_root (default_for_conversions)
    WHERE default_for_conversions;

CREATE TABLE conversion_job (
    id UUID PRIMARY KEY,
    requested_by UUID REFERENCES user_account(id) ON DELETE RESTRICT,
    source_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    output_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE RESTRICT,
    derived_asset_id UUID UNIQUE REFERENCES asset(id) ON DELETE RESTRICT,
    source_format VARCHAR(10) NOT NULL CHECK (source_format IN ('FB2', 'MOBI')),
    target_format VARCHAR(10) NOT NULL CHECK (target_format = 'EPUB'),
    route VARCHAR(40) NOT NULL CHECK (route IN ('FB2_TO_EPUB_V1', 'MOBI_TO_EPUB_V1')),
    route_version VARCHAR(100) NOT NULL CHECK (btrim(route_version) <> ''),
    route_key CHAR(64) NOT NULL UNIQUE CHECK (route_key ~ '^[0-9a-f]{64}$'),
    effective_settings JSONB NOT NULL CHECK (jsonb_typeof(effective_settings) = 'object'),
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    source_byte_size BIGINT NOT NULL CHECK (source_byte_size > 0 AND source_byte_size <= 536870912),
    staged_input_name VARCHAR(100) NOT NULL UNIQUE
        CHECK (staged_input_name ~ '^[0-9a-f-]{36}\.(fb2|mobi)$'),
    staged_output_name VARCHAR(100)
        CHECK (staged_output_name IS NULL OR staged_output_name ~ '^[0-9a-f-]{36}\.epub$'),
    uploaded_output_sha256 CHAR(64)
        CHECK (uploaded_output_sha256 IS NULL OR uploaded_output_sha256 ~ '^[0-9a-f]{64}$'),
    uploaded_output_byte_size BIGINT
        CHECK (uploaded_output_byte_size IS NULL OR (uploaded_output_byte_size > 0 AND uploaded_output_byte_size <= 1073741824)),
    state VARCHAR(20) NOT NULL
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED_SAFE', 'CANCELLED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0 AND attempt_count <= 3),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(100),
    safe_diagnostic VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CHECK ((staged_output_name IS NULL) = (uploaded_output_sha256 IS NULL)),
    CHECK ((staged_output_name IS NULL) = (uploaded_output_byte_size IS NULL)),
    CHECK ((state = 'SUCCEEDED') = (derived_asset_id IS NOT NULL))
);

CREATE INDEX conversion_job_claim_idx
    ON conversion_job (created_at, id)
    WHERE state IN ('QUEUED', 'RUNNING');

UPDATE yurlib_metadata
SET metadata_value = '12'
WHERE metadata_key = 'schema_version';
