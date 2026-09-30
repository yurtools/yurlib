ALTER TABLE library_root
    DROP CONSTRAINT library_root_singleton_unique,
    DROP CONSTRAINT library_root_singleton_check,
    DROP COLUMN singleton_key;

ALTER TABLE library_root
    DROP CONSTRAINT library_root_mode_check;

UPDATE library_root
SET mode = 'READ_ONLY_SOURCE'
WHERE mode = 'READ_ONLY';

ALTER TABLE library_root
    ALTER COLUMN mode SET DEFAULT 'READ_ONLY_SOURCE',
    ADD CONSTRAINT library_root_mode_check
        CHECK (mode IN ('READ_ONLY_SOURCE', 'MANAGED_OUTPUT'));

CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    normalized_username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(500) NOT NULL CHECK (btrim(password_hash) <> ''),
    owner BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    authorization_version BIGINT NOT NULL DEFAULT 1 CHECK (authorization_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (normalized_username),
    CHECK (btrim(username) <> ''),
    CHECK (normalized_username = lower(btrim(username)))
);

CREATE UNIQUE INDEX user_account_single_owner_idx
    ON user_account (owner)
    WHERE owner;

CREATE TABLE user_capability (
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    capability VARCHAR(50) NOT NULL
        CHECK (capability IN ('MANAGE_INGESTION_SOURCES', 'CURATE_CATALOG')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, capability)
);

CREATE TABLE user_root_deny (
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    library_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, library_root_id)
);

CREATE TABLE security_audit_event (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
    target_user_id UUID REFERENCES user_account(id) ON DELETE RESTRICT,
    event_type VARCHAR(50) NOT NULL CHECK (btrim(event_type) <> ''),
    details JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX security_audit_actor_created_idx
    ON security_audit_event (actor_user_id, created_at DESC);

CREATE TRIGGER security_audit_event_immutable
    BEFORE UPDATE OR DELETE ON security_audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_catalog_evidence_mutation();

UPDATE yurlib_metadata
SET metadata_value = '5'
WHERE metadata_key = 'schema_version';
