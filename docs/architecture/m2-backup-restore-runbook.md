# M2 Backup and Restore Runbook

## Scope

`ops/yurlib_recovery.py` creates and restores the complete state that Yurlib owns:

- a consistent PostgreSQL custom-format dump;
- every configured `MANAGED_OUTPUT` root, including covers and conversions;
- root inventory and derivation/location lineage exports;
- a versioned JSON manifest with exact paths, file sizes, modes, and SHA-256 hashes.

Read-only source books and disposable document-worker staging files are intentionally excluded. Back up source storage through its storage system. Protect the Yurlib backup destination with operator-managed encryption and access control.

## Preconditions

- Run the command from the repository or deployment directory used by Docker Compose.
- Keep the `postgres` service running.
- Stop `server` and `document-worker`. The command refuses to continue while either service runs.
- Supply every managed root as `UUID=/absolute/host/path`. The command compares these IDs with PostgreSQL and refuses incomplete or extra mappings.
- Keep credentials in the deployment environment. The command uses `YURLIB_DB_NAME` and `YURLIB_DB_USERNAME` and does not write credentials to the backup.

## Create a backup

First list the managed roots:

```bash
docker compose exec -T postgres psql \
  --username="${YURLIB_DB_USERNAME:-yurlib}" \
  --dbname="${YURLIB_DB_NAME:-yurlib}" \
  --command="SELECT id, name, mount_alias, relative_base_path FROM library_root WHERE mode = 'MANAGED_OUTPUT' ORDER BY id"
```

Stop writers, then create the backup. Repeat `--managed-root` when more than one managed root exists.

```bash
docker compose --profile worker stop server document-worker

./ops/yurlib_recovery.py backup /absolute/encrypted-destination/yurlib-backup \
  --managed-root d1fb3706-262d-42d4-a8c6-b9e49663464d=/absolute/path/to/.local/managed
```

The destination must not exist. The command builds a private temporary directory, verifies the finished backup, and only then renames it to the requested destination. A failed backup does not replace an earlier backup.

Restart services after the command succeeds:

```bash
docker compose --profile worker up --detach
```

## Verify without restoring

Verification is read-only. It rejects changed or unexpected files, symbolic links, special files, unsafe paths, missing roots, and unsupported manifest versions.

```bash
./ops/yurlib_recovery.py verify /absolute/encrypted-destination/yurlib-backup
```

Run verification after copying a backup to new storage and before every restore.

## Restore into an empty deployment

Restore is intentionally strict:

- the PostgreSQL service must have no Yurlib application tables;
- each target managed-root path must not exist;
- application and worker services must be stopped;
- all backup hashes and exact file inventories must pass before PostgreSQL changes;
- supplied root IDs must exactly match the backup.

Create a fresh deployment and PostgreSQL volume without starting `server`. Then run:

```bash
docker compose up --detach postgres

./ops/yurlib_recovery.py restore /absolute/encrypted-destination/yurlib-backup \
  --managed-root d1fb3706-262d-42d4-a8c6-b9e49663464d=/absolute/new/path/to/.local/managed
```

Managed files are first copied to private sibling staging directories. After the database restore and recovery reconciliation succeed, those directories are renamed to their final paths.

## Required post-restore actions

Restore preserves users, password hashes, capabilities, root denies, personal state, catalog curation and audit history, provenance, covers, conversions, and completed job history.

The recovery reconciliation deliberately:

- marks every `READ_ONLY_SOURCE` root and its asset locations unavailable;
- cancels interrupted scans and ingestion tasks;
- marks queued or running staged metadata, cover, and conversion jobs failed-safe because staging is not backed up;
- preserves already-published managed assets and their lineage.

After restore:

1. Mount each original source at its configured alias.
2. Verify its `.yurlib-root-id` marker through the normal root configuration workflow.
3. Confirm the root becomes available before scanning.
4. Rescan to recreate any interrupted staged work.
5. Sign in as both owner and a restricted user; verify root-denied works, assets, jobs, downloads, covers, conversions, collections, and totals remain non-disclosing.
6. Download one original and one restored managed asset and compare the expected hashes.

Do not start services if verification or restore reports an error. Retain the untouched backup and diagnose the disposable target deployment.
