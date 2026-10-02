ALTER TABLE work
    ADD COLUMN merged_into_id UUID REFERENCES work(id) ON DELETE RESTRICT,
    ADD CONSTRAINT work_not_merged_into_self CHECK (merged_into_id IS NULL OR merged_into_id <> id);

ALTER TABLE edition
    ADD COLUMN merged_into_id UUID REFERENCES edition(id) ON DELETE RESTRICT,
    ADD CONSTRAINT edition_not_merged_into_self CHECK (merged_into_id IS NULL OR merged_into_id <> id);

ALTER TABLE contributor
    ADD COLUMN merged_into_id UUID REFERENCES contributor(id) ON DELETE RESTRICT,
    ADD CONSTRAINT contributor_not_merged_into_self CHECK (merged_into_id IS NULL OR merged_into_id <> id);

ALTER TABLE user_work_read_state
    DROP CONSTRAINT IF EXISTS user_work_read_state_completed_edition_id_work_id_fkey,
    ADD CONSTRAINT user_work_read_state_completed_edition_work_fk
        FOREIGN KEY (completed_edition_id, work_id)
        REFERENCES edition(id, work_id) ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE catalog_merge_operation (
    id UUID PRIMARY KEY,
    operation_type VARCHAR(30) NOT NULL
        CHECK (operation_type IN ('WORK_MERGE', 'EDITION_MERGE', 'CONTRIBUTOR_MERGE')),
    subject_type VARCHAR(20) NOT NULL
        CHECK (subject_type IN ('WORK', 'EDITION', 'CONTRIBUTOR')),
    survivor_id UUID NOT NULL,
    source_id UUID NOT NULL,
    survivor_version BIGINT NOT NULL CHECK (survivor_version >= 0),
    source_version BIGINT NOT NULL CHECK (source_version >= 0),
    actor_id UUID NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
    reason VARCHAR(1000) NOT NULL CHECK (btrim(reason) <> ''),
    idempotency_key UUID NOT NULL UNIQUE,
    before_state JSONB NOT NULL CHECK (jsonb_typeof(before_state) = 'object'),
    after_state JSONB NOT NULL CHECK (jsonb_typeof(after_state) = 'object'),
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
        CHECK (status IN ('APPLIED', 'UNDONE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    undone_at TIMESTAMPTZ,
    undone_by UUID REFERENCES user_account(id) ON DELETE RESTRICT,
    undo_reason VARCHAR(1000),
    CHECK (survivor_id <> source_id),
    CHECK (
        (status = 'APPLIED' AND undone_at IS NULL AND undone_by IS NULL AND undo_reason IS NULL)
        OR (status = 'UNDONE' AND undone_at IS NOT NULL AND undone_by IS NOT NULL
            AND btrim(undo_reason) <> '')
    )
);

CREATE INDEX catalog_merge_operation_subject_idx
    ON catalog_merge_operation (subject_type, survivor_id, source_id, created_at DESC);

CREATE TABLE catalog_duplicate_decision (
    id UUID PRIMARY KEY,
    subject_type VARCHAR(20) NOT NULL
        CHECK (subject_type IN ('WORK', 'EDITION', 'CONTRIBUTOR')),
    first_subject_id UUID NOT NULL,
    second_subject_id UUID NOT NULL,
    decision VARCHAR(20) NOT NULL CHECK (decision = 'NOT_SAME'),
    rule_name VARCHAR(100) NOT NULL CHECK (btrim(rule_name) <> ''),
    rule_version VARCHAR(100) NOT NULL CHECK (btrim(rule_version) <> ''),
    actor_id UUID NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
    reason VARCHAR(1000) NOT NULL CHECK (btrim(reason) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (first_subject_id < second_subject_id),
    UNIQUE (subject_type, first_subject_id, second_subject_id, rule_name, rule_version)
);

UPDATE yurlib_metadata
SET metadata_value = '11'
WHERE metadata_key = 'schema_version';
