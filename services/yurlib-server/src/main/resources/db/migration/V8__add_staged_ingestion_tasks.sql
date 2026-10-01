ALTER TABLE scan_job
    ADD COLUMN discovery_completed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE ingestion_root_schedule (
    library_root_id UUID PRIMARY KEY REFERENCES library_root(id) ON DELETE CASCADE,
    last_claimed_at TIMESTAMPTZ,
    claim_sequence BIGINT NOT NULL DEFAULT 0 CHECK (claim_sequence >= 0)
);

CREATE TABLE ingestion_task (
    id UUID PRIMARY KEY,
    scan_job_id UUID NOT NULL REFERENCES scan_job(id) ON DELETE CASCADE,
    library_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE RESTRICT,
    stage VARCHAR(20) NOT NULL CHECK (stage = 'METADATA'),
    idempotency_key CHAR(64) NOT NULL CHECK (idempotency_key ~ '^[0-9a-f]{64}$'),
    normalized_relative_path VARCHAR(2000) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    modified_at TIMESTAMPTZ NOT NULL,
    file_key VARCHAR(1000),
    memory_reservation_mib INTEGER NOT NULL CHECK (memory_reservation_mib IN (64, 128)),
    state VARCHAR(20) NOT NULL
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (scan_job_id, idempotency_key),
    UNIQUE (scan_job_id, normalized_relative_path),
    CHECK (normalized_relative_path <> ''),
    CHECK (normalized_relative_path !~ '(^|/)\.\.?(/|$)'),
    CHECK (normalized_relative_path !~ '^/'),
    CHECK (normalized_relative_path !~ '/$'),
    CHECK (normalized_relative_path NOT LIKE '%//%'),
    CHECK (position(chr(92) in normalized_relative_path) = 0),
    CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);

CREATE INDEX ingestion_task_claim_idx
    ON ingestion_task (library_root_id, created_at, id)
    WHERE state IN ('QUEUED', 'RUNNING');
CREATE INDEX ingestion_task_job_state_idx ON ingestion_task (scan_job_id, state);

UPDATE yurlib_metadata
SET metadata_value = '8'
WHERE metadata_key = 'schema_version';
