ALTER TABLE library_root
    ADD COLUMN singleton_key SMALLINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT library_root_singleton_check CHECK (singleton_key = 1),
    ADD CONSTRAINT library_root_singleton_unique UNIQUE (singleton_key);

UPDATE yurlib_metadata
SET metadata_value = '3'
WHERE metadata_key = 'schema_version';
