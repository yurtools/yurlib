ALTER TABLE work
    ADD COLUMN content_kind VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (content_kind IN ('BOOK', 'DOCUMENT', 'UNKNOWN')),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

UPDATE work
SET content_kind = 'BOOK'
WHERE EXISTS (
    SELECT 1
    FROM edition
    JOIN asset ON asset.edition_id = edition.id
    WHERE edition.work_id = work.id
      AND asset.format IN ('EPUB', 'FB2', 'MOBI')
);

ALTER TABLE edition
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

ALTER TABLE asset
    DROP CONSTRAINT asset_format_check,
    DROP CONSTRAINT asset_derivation_check,
    ADD CONSTRAINT asset_format_check
        CHECK (format IN ('EPUB', 'FB2', 'MOBI', 'PDF', 'DOCX', 'DJVU')),
    ADD CONSTRAINT asset_derivation_check
        CHECK (derivation IN ('ORIGINAL', 'DERIVED')),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

CREATE UNIQUE INDEX asset_exact_content_hash_idx
    ON asset (content_hash)
    WHERE content_hash IS NOT NULL;

CREATE TABLE asset_derivation_source (
    derived_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    source_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    source_ordinal INTEGER NOT NULL DEFAULT 0 CHECK (source_ordinal >= 0),
    purpose VARCHAR(50) NOT NULL CHECK (btrim(purpose) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (derived_asset_id, source_asset_id),
    UNIQUE (derived_asset_id, source_ordinal),
    CHECK (derived_asset_id <> source_asset_id)
);

CREATE TABLE contributor (
    id UUID PRIMARY KEY,
    display_name VARCHAR(1000) NOT NULL CHECK (btrim(display_name) <> ''),
    kind VARCHAR(20) NOT NULL DEFAULT 'PERSON'
        CHECK (kind IN ('PERSON', 'ORGANIZATION', 'UNRESOLVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE contributor_alias (
    id UUID PRIMARY KEY,
    contributor_id UUID NOT NULL REFERENCES contributor(id) ON DELETE RESTRICT,
    alias VARCHAR(1000) NOT NULL CHECK (btrim(alias) <> ''),
    normalized_alias VARCHAR(1000) NOT NULL CHECK (btrim(normalized_alias) <> ''),
    script VARCHAR(50),
    language VARCHAR(35),
    source_observation_id UUID REFERENCES metadata_observation(id) ON DELETE RESTRICT,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (contributor_id, normalized_alias)
);

CREATE TABLE work_contributor (
    work_id UUID NOT NULL REFERENCES work(id) ON DELETE RESTRICT,
    contributor_id UUID NOT NULL REFERENCES contributor(id) ON DELETE RESTRICT,
    role VARCHAR(20) NOT NULL
        CHECK (role IN ('AUTHOR', 'EDITOR', 'TRANSLATOR', 'ILLUSTRATOR', 'OTHER')),
    ordinal INTEGER NOT NULL DEFAULT 0 CHECK (ordinal >= 0),
    source_observation_id UUID REFERENCES metadata_observation(id) ON DELETE RESTRICT,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (work_id, contributor_id, role),
    UNIQUE (work_id, role, ordinal)
);

CREATE TABLE edition_contributor (
    edition_id UUID NOT NULL REFERENCES edition(id) ON DELETE RESTRICT,
    contributor_id UUID NOT NULL REFERENCES contributor(id) ON DELETE RESTRICT,
    role VARCHAR(20) NOT NULL
        CHECK (role IN ('AUTHOR', 'EDITOR', 'TRANSLATOR', 'ILLUSTRATOR', 'OTHER')),
    ordinal INTEGER NOT NULL DEFAULT 0 CHECK (ordinal >= 0),
    source_observation_id UUID REFERENCES metadata_observation(id) ON DELETE RESTRICT,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (edition_id, contributor_id, role),
    UNIQUE (edition_id, role, ordinal)
);

CREATE TABLE edition_identifier (
    id UUID PRIMARY KEY,
    edition_id UUID NOT NULL REFERENCES edition(id) ON DELETE RESTRICT,
    identifier_type VARCHAR(30) NOT NULL
        CHECK (identifier_type IN ('ISBN_10', 'ISBN_13', 'DOI', 'UUID', 'URI', 'SOURCE_LOCAL', 'OTHER')),
    scheme VARCHAR(100) NOT NULL CHECK (btrim(scheme) <> ''),
    observed_value VARCHAR(2000) NOT NULL CHECK (btrim(observed_value) <> ''),
    normalized_value VARCHAR(2000),
    source_observation_id UUID REFERENCES metadata_observation(id) ON DELETE RESTRICT,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (edition_id, scheme, observed_value)
);

CREATE TABLE metadata_observation_set (
    id UUID PRIMARY KEY,
    source_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    source_root_id UUID NOT NULL REFERENCES library_root(id) ON DELETE RESTRICT,
    parser_name VARCHAR(100) NOT NULL CHECK (btrim(parser_name) <> ''),
    parser_version VARCHAR(100) NOT NULL CHECK (btrim(parser_version) <> ''),
    extraction_version VARCHAR(100) NOT NULL CHECK (btrim(extraction_version) <> ''),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE metadata_observation
    DROP CONSTRAINT metadata_observation_subject_type_check,
    ADD CONSTRAINT metadata_observation_subject_type_check
        CHECK (subject_type IN ('WORK', 'EDITION', 'ASSET', 'CONTRIBUTOR')),
    ADD COLUMN observation_set_id UUID,
    ADD COLUMN source_asset_id UUID,
    ADD COLUMN source_root_id UUID,
    ADD COLUMN value_state VARCHAR(20) NOT NULL DEFAULT 'PRESENT'
        CHECK (value_state IN ('PRESENT', 'ABSENT')),
    ADD COLUMN value_ordinal INTEGER NOT NULL DEFAULT 0 CHECK (value_ordinal >= 0),
    ADD COLUMN absence_reason VARCHAR(1000),
    ALTER COLUMN observed_value DROP NOT NULL,
    ADD CONSTRAINT metadata_observation_value_check CHECK (
        (value_state = 'PRESENT' AND observed_value IS NOT NULL)
        OR (value_state = 'ABSENT' AND observed_value IS NULL AND btrim(absence_reason) <> '')
    );

UPDATE metadata_observation observation
SET source_asset_id = observation.subject_id
WHERE observation.subject_type = 'ASSET';

UPDATE metadata_observation observation
SET source_asset_id = source.asset_id
FROM (
    SELECT DISTINCT ON (edition.id) edition.id AS edition_id, asset.id AS asset_id
    FROM edition
    JOIN asset ON asset.edition_id = edition.id
    JOIN asset_location ON asset_location.asset_id = asset.id
    ORDER BY edition.id, asset.created_at, asset.id
) source
WHERE observation.subject_type = 'EDITION'
  AND observation.subject_id = source.edition_id;

UPDATE metadata_observation observation
SET source_asset_id = source.asset_id
FROM (
    SELECT DISTINCT ON (work.id) work.id AS work_id, asset.id AS asset_id
    FROM work
    JOIN edition ON edition.work_id = work.id
    JOIN asset ON asset.edition_id = edition.id
    JOIN asset_location ON asset_location.asset_id = asset.id
    ORDER BY work.id, asset.created_at, asset.id
) source
WHERE observation.subject_type = 'WORK'
  AND observation.subject_id = source.work_id;

UPDATE metadata_observation observation
SET source_root_id = source.library_root_id
FROM (
    SELECT DISTINCT ON (asset_id) asset_id, library_root_id
    FROM asset_location
    ORDER BY asset_id, created_at, id
) source
WHERE observation.source_asset_id = source.asset_id;

INSERT INTO metadata_observation_set (
    id, source_asset_id, source_root_id, parser_name, parser_version,
    extraction_version, observed_at, created_at
)
SELECT observation.id,
       observation.source_asset_id,
       observation.source_root_id,
       observation.parser_name,
       observation.parser_version,
       asset.extraction_version,
       observation.observed_at,
       observation.observed_at
FROM metadata_observation observation
JOIN asset ON asset.id = observation.source_asset_id;

UPDATE metadata_observation
SET observation_set_id = id;

ALTER TABLE metadata_observation
    ALTER COLUMN observation_set_id SET NOT NULL,
    ALTER COLUMN source_asset_id SET NOT NULL,
    ALTER COLUMN source_root_id SET NOT NULL,
    ADD CONSTRAINT metadata_observation_set_fk
        FOREIGN KEY (observation_set_id) REFERENCES metadata_observation_set(id) ON DELETE RESTRICT,
    ADD CONSTRAINT metadata_observation_asset_fk
        FOREIGN KEY (source_asset_id) REFERENCES asset(id) ON DELETE RESTRICT,
    ADD CONSTRAINT metadata_observation_root_fk
        FOREIGN KEY (source_root_id) REFERENCES library_root(id) ON DELETE RESTRICT;

CREATE INDEX metadata_observation_source_idx
    ON metadata_observation (source_asset_id, observation_set_id, field_name);

CREATE TABLE metadata_normalized_fact (
    id UUID PRIMARY KEY,
    source_observation_id UUID NOT NULL REFERENCES metadata_observation(id) ON DELETE RESTRICT,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('WORK', 'EDITION', 'ASSET', 'CONTRIBUTOR')),
    field_name VARCHAR(100) NOT NULL CHECK (btrim(field_name) <> ''),
    value_state VARCHAR(20) NOT NULL CHECK (value_state IN ('PRESENT', 'ABSENT')),
    normalized_value TEXT,
    value_type VARCHAR(50) NOT NULL CHECK (btrim(value_type) <> ''),
    normalizer_name VARCHAR(100) NOT NULL CHECK (btrim(normalizer_name) <> ''),
    normalizer_version VARCHAR(100) NOT NULL CHECK (btrim(normalizer_version) <> ''),
    confidence VARCHAR(20) NOT NULL CHECK (confidence IN ('EXACT', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
    supersedes_fact_id UUID REFERENCES metadata_normalized_fact(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (value_state = 'PRESENT' AND normalized_value IS NOT NULL)
        OR (value_state = 'ABSENT' AND normalized_value IS NULL)
    )
);

CREATE INDEX metadata_normalized_fact_subject_idx
    ON metadata_normalized_fact (subject_type, subject_id, field_name, created_at DESC);

CREATE TABLE metadata_resolved_value (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('WORK', 'EDITION', 'ASSET', 'CONTRIBUTOR')),
    field_name VARCHAR(100) NOT NULL CHECK (btrim(field_name) <> ''),
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('PRESENT', 'ABSENT', 'CONFLICT')),
    resolved_value TEXT,
    selected_fact_id UUID REFERENCES metadata_normalized_fact(id) ON DELETE RESTRICT,
    alternatives JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(alternatives) = 'array'),
    resolver_name VARCHAR(100) NOT NULL CHECK (btrim(resolver_name) <> ''),
    resolver_version VARCHAR(100) NOT NULL CHECK (btrim(resolver_version) <> ''),
    confidence VARCHAR(20) NOT NULL CHECK (confidence IN ('EXACT', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
    resolution_version BIGINT NOT NULL CHECK (resolution_version > 0),
    supersedes_value_id UUID REFERENCES metadata_resolved_value(id) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (subject_type, subject_id, field_name, resolution_version),
    CHECK (
        (outcome = 'PRESENT' AND resolved_value IS NOT NULL AND selected_fact_id IS NOT NULL)
        OR (outcome IN ('ABSENT', 'CONFLICT') AND resolved_value IS NULL)
    )
);

CREATE UNIQUE INDEX metadata_resolved_value_active_idx
    ON metadata_resolved_value (subject_type, subject_id, field_name)
    WHERE active;

CREATE TABLE metadata_curated_override (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('WORK', 'EDITION', 'ASSET', 'CONTRIBUTOR')),
    field_name VARCHAR(100) NOT NULL CHECK (btrim(field_name) <> ''),
    value_state VARCHAR(20) NOT NULL CHECK (value_state IN ('PRESENT', 'ABSENT')),
    curated_value TEXT,
    actor_id UUID NOT NULL,
    reason VARCHAR(1000) NOT NULL CHECK (btrim(reason) <> ''),
    override_version BIGINT NOT NULL CHECK (override_version > 0),
    supersedes_override_id UUID REFERENCES metadata_curated_override(id) ON DELETE RESTRICT,
    undo_of_override_id UUID REFERENCES metadata_curated_override(id) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (subject_type, subject_id, field_name, override_version),
    CHECK (
        (value_state = 'PRESENT' AND curated_value IS NOT NULL)
        OR (value_state = 'ABSENT' AND curated_value IS NULL)
    )
);

CREATE UNIQUE INDEX metadata_curated_override_active_idx
    ON metadata_curated_override (subject_type, subject_id, field_name)
    WHERE active;

CREATE TABLE catalog_redirect (
    id UUID PRIMARY KEY,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('WORK', 'EDITION', 'CONTRIBUTOR')),
    former_id UUID NOT NULL,
    canonical_id UUID NOT NULL,
    reason VARCHAR(30) NOT NULL CHECK (reason IN ('MERGE', 'SPLIT_REPLACEMENT', 'DUPLICATE')),
    actor_id UUID,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at TIMESTAMPTZ,
    UNIQUE (subject_type, former_id, version),
    CHECK (former_id <> canonical_id),
    CHECK ((active AND retired_at IS NULL) OR (NOT active AND retired_at IS NOT NULL))
);

CREATE UNIQUE INDEX catalog_redirect_active_idx
    ON catalog_redirect (subject_type, former_id)
    WHERE active;

CREATE TABLE catalog_audit_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(30) NOT NULL CHECK (btrim(aggregate_type) <> ''),
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL CHECK (btrim(event_type) <> ''),
    actor_id UUID,
    correlation_id VARCHAR(100) NOT NULL CHECK (btrim(correlation_id) <> ''),
    reason VARCHAR(1000),
    before_state JSONB,
    after_state JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (before_state IS NULL OR jsonb_typeof(before_state) = 'object'),
    CHECK (after_state IS NULL OR jsonb_typeof(after_state) = 'object')
);

CREATE INDEX catalog_audit_event_aggregate_idx
    ON catalog_audit_event (aggregate_type, aggregate_id, created_at DESC);

INSERT INTO contributor (id, display_name, kind, created_at, updated_at)
SELECT source.id, source.observed_value, 'UNRESOLVED', source.first_observed_at, source.last_observed_at
FROM (
    SELECT DISTINCT ON (observation.subject_id, observation.observed_value)
           observation.id,
           observation.subject_id,
           observation.observed_value,
           min(observation.observed_at) OVER (
               PARTITION BY observation.subject_id, observation.observed_value
           ) AS first_observed_at,
           max(observation.observed_at) OVER (
               PARTITION BY observation.subject_id, observation.observed_value
           ) AS last_observed_at
    FROM metadata_observation observation
    WHERE observation.subject_type = 'WORK'
      AND observation.field_name = 'contributor'
      AND observation.value_state = 'PRESENT'
    ORDER BY observation.subject_id, observation.observed_value, observation.observed_at, observation.id
) source;

INSERT INTO contributor_alias (
    id, contributor_id, alias, normalized_alias, source_observation_id, created_at, updated_at
)
SELECT gen_random_uuid(), contributor.id, contributor.display_name, lower(btrim(contributor.display_name)),
       contributor.id, contributor.created_at, contributor.updated_at
FROM contributor;

INSERT INTO work_contributor (
    work_id, contributor_id, role, ordinal, source_observation_id, created_at
)
SELECT observation.subject_id,
       contributor.id,
       'AUTHOR',
       row_number() OVER (PARTITION BY observation.subject_id ORDER BY observation.value_ordinal, observation.id) - 1,
       observation.id,
       observation.observed_at
FROM contributor
JOIN metadata_observation observation ON observation.id = contributor.id
WHERE observation.subject_type = 'WORK'
  AND observation.field_name = 'contributor'
  AND observation.value_state = 'PRESENT'
ON CONFLICT (work_id, contributor_id, role) DO NOTHING;

INSERT INTO edition_identifier (
    id, edition_id, identifier_type, scheme, observed_value, source_observation_id, created_at
)
SELECT gen_random_uuid(),
       observation.subject_id,
       CASE
           WHEN lower(split_part(observation.field_name, ':', 2)) IN ('isbn', 'isbn13', 'isbn-13')
                AND length(regexp_replace(observation.observed_value, '[^0-9]', '', 'g')) = 13
               THEN 'ISBN_13'
           WHEN lower(split_part(observation.field_name, ':', 2)) IN ('isbn', 'isbn10', 'isbn-10')
                AND length(regexp_replace(observation.observed_value, '[^0-9Xx]', '', 'g')) = 10
               THEN 'ISBN_10'
           ELSE 'OTHER'
       END,
       split_part(observation.field_name, ':', 2),
       observation.observed_value,
       observation.id,
       observation.observed_at
FROM metadata_observation observation
WHERE observation.subject_type = 'EDITION'
  AND observation.field_name LIKE 'identifier:%'
  AND observation.value_state = 'PRESENT'
ON CONFLICT (edition_id, scheme, observed_value) DO NOTHING;

CREATE VIEW catalog_metadata_display AS
SELECT DISTINCT ON (candidate.subject_type, candidate.subject_id, candidate.field_name)
       candidate.subject_type,
       candidate.subject_id,
       candidate.field_name,
       candidate.value_state,
       candidate.display_value,
       candidate.metadata_source,
       candidate.source_record_id
FROM (
    SELECT curated.subject_type,
           curated.subject_id,
           curated.field_name,
           curated.value_state,
           curated.curated_value AS display_value,
           'CURATED'::varchar AS metadata_source,
           curated.id AS source_record_id,
           1 AS precedence,
           curated.created_at AS source_timestamp
    FROM metadata_curated_override curated
    WHERE curated.active
    UNION ALL
    SELECT resolved.subject_type,
           resolved.subject_id,
           resolved.field_name,
           resolved.outcome AS value_state,
           resolved.resolved_value AS display_value,
           'RESOLVED'::varchar AS metadata_source,
           resolved.id AS source_record_id,
           2 AS precedence,
           resolved.created_at AS source_timestamp
    FROM metadata_resolved_value resolved
    WHERE resolved.active
    UNION ALL
    SELECT observation.subject_type,
           observation.subject_id,
           observation.field_name,
           observation.value_state,
           observation.observed_value AS display_value,
           'OBSERVED'::varchar AS metadata_source,
           observation.id AS source_record_id,
           3 AS precedence,
           observation.observed_at AS source_timestamp
    FROM metadata_observation observation
) candidate
ORDER BY candidate.subject_type,
         candidate.subject_id,
         candidate.field_name,
         candidate.precedence,
         candidate.source_timestamp DESC,
         candidate.source_record_id DESC;

CREATE FUNCTION reject_catalog_evidence_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'catalog evidence is append-only';
END;
$$;

CREATE TRIGGER metadata_observation_immutable
    BEFORE UPDATE OR DELETE ON metadata_observation
    FOR EACH ROW EXECUTE FUNCTION reject_catalog_evidence_mutation();

CREATE TRIGGER metadata_observation_set_immutable
    BEFORE UPDATE OR DELETE ON metadata_observation_set
    FOR EACH ROW EXECUTE FUNCTION reject_catalog_evidence_mutation();

CREATE TRIGGER metadata_normalized_fact_immutable
    BEFORE UPDATE OR DELETE ON metadata_normalized_fact
    FOR EACH ROW EXECUTE FUNCTION reject_catalog_evidence_mutation();

CREATE TRIGGER catalog_audit_event_immutable
    BEFORE UPDATE OR DELETE ON catalog_audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_catalog_evidence_mutation();

CREATE FUNCTION validate_asset_derivation_lineage()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_asset_id UUID;
    checked_derivation VARCHAR(20);
    source_count BIGINT;
BEGIN
    checked_asset_id := CASE
        WHEN TG_TABLE_NAME = 'asset'
            THEN COALESCE(to_jsonb(NEW) ->> 'id', to_jsonb(OLD) ->> 'id')::uuid
        ELSE COALESCE(
            to_jsonb(NEW) ->> 'derived_asset_id',
            to_jsonb(OLD) ->> 'derived_asset_id'
        )::uuid
    END;

    SELECT derivation
    INTO checked_derivation
    FROM asset
    WHERE id = checked_asset_id;

    IF checked_derivation IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT count(*)
    INTO source_count
    FROM asset_derivation_source
    WHERE derived_asset_id = checked_asset_id;

    IF checked_derivation = 'DERIVED' AND source_count = 0 THEN
        RAISE EXCEPTION 'derived assets require source lineage';
    END IF;
    IF checked_derivation = 'ORIGINAL' AND source_count > 0 THEN
        RAISE EXCEPTION 'original assets cannot have source lineage';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER asset_derivation_lineage_from_asset
    AFTER INSERT OR UPDATE OF derivation ON asset
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_asset_derivation_lineage();

CREATE CONSTRAINT TRIGGER asset_derivation_lineage_from_source
    AFTER INSERT OR UPDATE OR DELETE ON asset_derivation_source
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_asset_derivation_lineage();

UPDATE yurlib_metadata
SET metadata_value = '4'
WHERE metadata_key = 'schema_version';
