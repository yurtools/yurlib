ALTER TABLE library_root
    ADD COLUMN default_for_covers BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT library_root_cover_default_check
        CHECK (NOT default_for_covers OR mode = 'MANAGED_OUTPUT');

CREATE UNIQUE INDEX library_root_one_cover_default_idx
    ON library_root (default_for_covers)
    WHERE default_for_covers;

CREATE TABLE cover_job (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES work(id) ON DELETE RESTRICT,
    source_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    output_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE RESTRICT,
    source_format VARCHAR(10) NOT NULL
        CHECK (source_format IN ('EPUB', 'FB2', 'MOBI', 'PDF', 'DOCX', 'DJVU')),
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    source_byte_size BIGINT NOT NULL CHECK (source_byte_size > 0 AND source_byte_size <= 268435456),
    staged_input_name VARCHAR(100) NOT NULL UNIQUE
        CHECK (staged_input_name ~ '^[0-9a-f-]{36}\.(epub|fb2|mobi|pdf|docx|djvu)$'),
    processor_version VARCHAR(100) NOT NULL CHECK (btrim(processor_version) <> ''),
    state VARCHAR(20) NOT NULL
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'UNAVAILABLE', 'FAILED_SAFE')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0 AND attempt_count <= 3),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    safe_diagnostic VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (source_asset_id, output_root_id, processor_version),
    CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);

CREATE INDEX cover_job_claim_idx
    ON cover_job (created_at, id)
    WHERE state IN ('QUEUED', 'RUNNING');

CREATE TABLE cover_derivative (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL UNIQUE REFERENCES cover_job(id) ON DELETE RESTRICT,
    work_id UUID NOT NULL REFERENCES work(id) ON DELETE RESTRICT,
    source_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    managed_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE RESTRICT,
    selection_kind VARCHAR(30) NOT NULL
        CHECK (selection_kind IN ('DECLARED_EMBEDDED', 'DOCX_THUMBNAIL', 'PDF_PAGE_ONE', 'DJVU_PAGE_ONE')),
    source_locator VARCHAR(1000) NOT NULL CHECK (btrim(source_locator) <> ''),
    input_sha256 CHAR(64) NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    output_sha256 CHAR(64) NOT NULL CHECK (output_sha256 ~ '^[0-9a-f]{64}$'),
    processor_name VARCHAR(100) NOT NULL CHECK (btrim(processor_name) <> ''),
    processor_version VARCHAR(100) NOT NULL CHECK (btrim(processor_version) <> ''),
    effective_limits JSONB NOT NULL CHECK (jsonb_typeof(effective_limits) = 'object'),
    width INTEGER NOT NULL CHECK (width > 0 AND width <= 1600),
    height INTEGER NOT NULL CHECK (height > 0 AND height <= 2400),
    media_type VARCHAR(30) NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    byte_size BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 16777216),
    normalized_relative_path VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (managed_root_id, normalized_relative_path),
    CHECK ((width::BIGINT * height::BIGINT) <= 8000000),
    CHECK (normalized_relative_path ~ '^covers/[0-9a-f]{2}/[0-9a-f]{64}\.(jpg|png)$'),
    CHECK (position(chr(92) in normalized_relative_path) = 0)
);

CREATE INDEX cover_derivative_work_idx
    ON cover_derivative (work_id, selection_kind, created_at, id);

CREATE TABLE work_cover_preference (
    work_id UUID PRIMARY KEY REFERENCES work(id) ON DELETE RESTRICT,
    source_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
    reason VARCHAR(1000) NOT NULL CHECK (btrim(reason) <> ''),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

UPDATE yurlib_metadata
SET metadata_value = '9'
WHERE metadata_key = 'schema_version';
