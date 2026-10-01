CREATE TABLE metadata_review_item (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL
        CHECK (subject_type IN ('WORK', 'EDITION', 'ASSET', 'CONTRIBUTOR')),
    field_name VARCHAR(100) NOT NULL CHECK (btrim(field_name) <> ''),
    source_observation_id UUID REFERENCES metadata_observation(id) ON DELETE RESTRICT,
    reason_code VARCHAR(50) NOT NULL
        CHECK (reason_code IN ('INVALID_VALUE', 'AMBIGUOUS_VALUE', 'CONFLICT')),
    detail VARCHAR(1000) NOT NULL CHECK (btrim(detail) <> ''),
    rule_name VARCHAR(100) NOT NULL CHECK (btrim(rule_name) <> ''),
    rule_version VARCHAR(100) NOT NULL CHECK (btrim(rule_version) <> ''),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    resolved_by UUID,
    resolution_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CHECK (
        (status = 'OPEN' AND resolved_by IS NULL AND resolved_at IS NULL)
        OR (status <> 'OPEN' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL
            AND btrim(resolution_reason) <> '')
    )
);

CREATE UNIQUE INDEX metadata_review_item_open_idx
    ON metadata_review_item (
        subject_type,
        subject_id,
        field_name,
        reason_code,
        rule_name,
        rule_version,
        COALESCE(source_observation_id, '00000000-0000-0000-0000-000000000000'::uuid)
    )
    WHERE status = 'OPEN';

CREATE INDEX metadata_review_item_queue_idx
    ON metadata_review_item (status, created_at, id);

CREATE TABLE catalog_tag (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    normalized_name VARCHAR(100) NOT NULL CHECK (btrim(normalized_name) <> ''),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (normalized_name)
);

CREATE TABLE work_tag (
    work_id UUID NOT NULL REFERENCES work(id) ON DELETE RESTRICT,
    tag_id UUID NOT NULL REFERENCES catalog_tag(id) ON DELETE RESTRICT,
    assigned_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (work_id, tag_id)
);

CREATE INDEX work_tag_tag_idx ON work_tag (tag_id, work_id);

ALTER TABLE contributor_alias
    ADD COLUMN curated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN actor_id UUID,
    ADD COLUMN reason VARCHAR(1000),
    ADD CONSTRAINT contributor_alias_curation_check CHECK (
        (NOT curated AND actor_id IS NULL AND reason IS NULL)
        OR (curated AND actor_id IS NOT NULL AND btrim(reason) <> '')
    );

UPDATE yurlib_metadata
SET metadata_value = '7'
WHERE metadata_key = 'schema_version';
