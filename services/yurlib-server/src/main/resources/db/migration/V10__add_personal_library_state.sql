ALTER TABLE edition
    ADD CONSTRAINT edition_id_work_unique UNIQUE (id, work_id);

ALTER TABLE user_account
    ADD COLUMN removed_at TIMESTAMPTZ;

CREATE TABLE user_contributor_favorite (
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    contributor_id UUID NOT NULL REFERENCES contributor(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, contributor_id)
);

CREATE INDEX user_contributor_favorite_created_idx
    ON user_contributor_favorite (user_id, created_at, contributor_id);

CREATE TABLE user_work_read_state (
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    work_id UUID NOT NULL REFERENCES work(id) ON DELETE RESTRICT,
    completed_edition_id UUID,
    completed_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, work_id),
    FOREIGN KEY (completed_edition_id, work_id)
        REFERENCES edition(id, work_id) ON DELETE RESTRICT
);

CREATE INDEX user_work_read_state_completed_idx
    ON user_work_read_state (user_id, completed_at DESC, work_id);

CREATE TABLE personal_collection (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    normalized_name VARCHAR(200) NOT NULL CHECK (btrim(normalized_name) <> ''),
    ordered BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, normalized_name)
);

CREATE INDEX personal_collection_user_idx
    ON personal_collection (user_id, normalized_name, id);

CREATE TABLE personal_collection_work (
    collection_id UUID NOT NULL REFERENCES personal_collection(id) ON DELETE CASCADE,
    work_id UUID NOT NULL REFERENCES work(id) ON DELETE RESTRICT,
    position INTEGER CHECK (position >= 0),
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (collection_id, work_id)
);

CREATE UNIQUE INDEX personal_collection_work_position_idx
    ON personal_collection_work (collection_id, position)
    WHERE position IS NOT NULL;

CREATE FUNCTION yurlib_user_can_access_work(
    requested_user_id UUID,
    requested_work_id UUID,
    unrestricted BOOLEAN
) RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
    WITH RECURSIVE candidate(asset_id) AS (
        SELECT DISTINCT visible_asset.id
        FROM edition visible_edition
        JOIN asset visible_asset ON visible_asset.edition_id = visible_edition.id
        JOIN asset_location visible_location ON visible_location.asset_id = visible_asset.id
        WHERE visible_edition.work_id = requested_work_id
          AND visible_location.availability = 'AVAILABLE'
          AND (unrestricted OR NOT EXISTS (
              SELECT 1
              FROM user_root_deny denied
              WHERE denied.user_id = requested_user_id
                AND denied.library_root_id = visible_location.library_root_id
          ))
    ), lineage(candidate_id, asset_id) AS (
        SELECT candidate.asset_id, source.source_asset_id
        FROM candidate
        JOIN asset_derivation_source source ON source.derived_asset_id = candidate.asset_id
        UNION
        SELECT lineage.candidate_id, source.source_asset_id
        FROM lineage
        JOIN asset_derivation_source source ON source.derived_asset_id = lineage.asset_id
    )
    SELECT EXISTS (
        SELECT 1
        FROM candidate
        WHERE unrestricted OR NOT EXISTS (
            SELECT 1
            FROM lineage
            JOIN asset_location source_location ON source_location.asset_id = lineage.asset_id
            JOIN user_root_deny denied_source
              ON denied_source.library_root_id = source_location.library_root_id
             AND denied_source.user_id = requested_user_id
            WHERE lineage.candidate_id = candidate.asset_id
        )
    );
$$;

UPDATE yurlib_metadata
SET metadata_value = '10'
WHERE metadata_key = 'schema_version';
