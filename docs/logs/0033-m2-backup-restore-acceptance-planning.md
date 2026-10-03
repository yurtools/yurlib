# M2 Backup, Restore, Security, and Walking-Skeleton Acceptance

- Status: Implementation complete; pull request preparation
- Started: 2026-10-02
- Branch: `feat/61-m2-acceptance`
- Issue: [#61](https://github.com/yurtools/yurlib/issues/61)
- Repository: `yurtools/yurlib`

## Prompt

> merged, continue

## Plan

1. Reconcile merged pull request #75, close its process log, update `main`, create the issue #61 branch, and preserve the unrelated Angular analytics preference.
2. Review M2 design sections 13 through 17, ADR-0005 through ADR-0010, all prior M2 acceptance evidence, current Compose storage boundaries, and issue #61 acceptance criteria.
3. Add operator-facing backup and empty-environment restore tooling for PostgreSQL and managed roots with a versioned manifest, hashes, modes, safe paths, and no read-only source bytes.
4. Prove restore rejection for traversal, unexpected or changed files, invalid versions, and incomplete artifacts; require source-root identity revalidation and reconcile interrupted jobs without duplicate publication.
5. Add a complete automated M2 walking-skeleton and cross-user security acceptance suite covering the six formats, curation, personal state, covers, conversion, direct identifiers, jobs, totals, downloads, collections, and session invalidation.
6. Run a disposable backup/restore drill, hostile-input and full repository verification, record benchmark observations without universal claims, and prepare manual desktop/mobile accessibility acceptance instructions.
7. Update operational and acceptance documentation, commit, push, and open a pull request that closes issue #61.

## Actions and results

- Confirmed pull request #75 squash-merged as `fc43fc02192f52469174a98dd910669abb3c187b`; issue #60 closed after all required checks passed.
- Fast-forwarded local `main` and preserved the unrelated user-owned `web/yurlib-web/angular.json` analytics preference.
- Reviewed M2 design sections 13 through 17, ADR-0005 through ADR-0010, Compose storage boundaries, and issue #61 acceptance criteria.
- Added `ops/yurlib_recovery.py`, a dependency-free Python 3 operator command with `backup`, `verify`, and `restore` modes.
  - Backup requires stopped application/worker services and a ready PostgreSQL service, compares supplied managed-root UUIDs with the database, streams a custom-format `pg_dump`, copies only regular managed files, exports root inventory and lineage, and writes a private version-1 manifest.
  - Verification rejects unsupported manifests, group/world-readable backup directories, changed or unexpected files, missing roots, symbolic links, special files, and unsafe relative paths.
  - Restore requires an empty PostgreSQL database and absent managed-root targets, verifies the complete backup before changes, stages managed trees before publication, streams the dump into `pg_restore`, marks read-only roots and locations unavailable, and safely terminates work whose disposable staging was not backed up.
- Added eight dependency-free recovery tests and made them part of the existing `Compose Configuration` CI job without changing its protected check name.
- Added the operator runbook and M2 acceptance report. Updated the M2 design to reflect the owner-approved deferral of NFS/NAS compatibility and performance evidence to post-M2 issue #70.
- Ran a destructive local restore drill with separate `yurlib_recovery_source_61` and `yurlib_recovery_restore_61` Compose projects on ports 55461/18061 and 55462/18062:
  - seeded an owner, source and managed roots, original and derived assets, lineage, a managed file, and interrupted scan, ingestion, cover, and conversion work;
  - stopped the source application, created and independently verified the backup, and restored it into a fresh PostgreSQL volume and absent managed-root path;
  - confirmed the restored owner, available managed root, unavailable source root and source locations, cancelled scan/ingestion, failed-safe cover/conversion jobs, exact managed-file SHA-256, and healthy restored server boot;
  - observed an initial PostgreSQL startup race before any restore mutation; added a bounded `pg_isready` wait and repeated the restore successfully;
  - stopped and removed both disposable Compose projects, networks, volumes, and ports, and moved the temporary drill directory to the desktop trash. Existing Yurlib volumes and library data were not used.
- Preserved the unrelated user-owned `web/yurlib-web/angular.json` analytics preference; it is not part of this change.

## Verification

- `python3 -m unittest discover -s ops/tests -p 'test_*.py' -v` — passed, 8 tests.
- Disposable backup/restore drill — passed after the readiness fix; restored server health was `UP` and managed source/restored hashes both equaled `c087dec111f4d337ca6573016e29bf1e95624bc50822af0900bdbc271675454e`.
- `./mvnw -B verify` — passed; server 174 tests with one opt-in benchmark skipped, worker 9 tests, coverage, architecture, formatting, OpenAPI compatibility, PMD, and SpotBugs passed.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages, 0 vulnerabilities. The existing npm install-script policy warning listed four blocked optional/native package scripts.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 18 tests.
- `npm --prefix web/yurlib-web run build` — passed, 380.82 kB initial bundle.
- `docker compose config --quiet` — passed.
- `git diff --check` — passed.

## Blockers

- Final GitHub CI and owner-facing M2 desktop/mobile acceptance remain pull-request gates.
