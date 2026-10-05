# M2 Backup, Restore, Security, and Walking-Skeleton Acceptance

- Status: Merged
- Started: 2026-10-02
- Branch: `feat/61-m2-acceptance`
- Issue: [#61](https://github.com/yurtools/yurlib/issues/61)
- Repository: `yurtools/yurlib`
- Commits: `7e90354`, `6c35f2c`, `c9f7f97`
- Pull request: [#76](https://github.com/yurtools/yurlib/pull/76)
- Squash commit: `421b72c3f90270404133aab6c3fd5a77ff7efc50`

## Prompt

> merged, continue

> proceed

> Retest passed at `c9f7f97c020825c4388dca490fb74f592f0f7398`.
>
> | Area | Desktop | Mobile |
> |---|---|---|
> | PDF ingestion and generated cover | Pass | Pass |
> | Accessible cover fallback | Pass | Pass |
> | Edition-specific read evidence/persistence/clear | Pass | Pass |
> | Favorite persistence/removal | Pass | Pass |
> | FB2 → EPUB conversion and download | Pass | Pass |
> | Merge and automatic undo | Pass | Pass |
> | Post-merge title correction | Pass | Pass |
> | Guided-split protection | Pass | Pass |
> | Restricted-reader projection | Pass | Pass |
> | Horizontal overflow check | `true` | `true` |
> | Browser console | Pass | Pass |
> | Backend/worker logs | Pass | Pass |
>
> Visible guided-split guidance:
>
> > Automatic undo is unavailable; this merge requires a guided split.
> > Guided split required
> > The merged catalog state changed after this operation; use a guided split.
>
> Restricted access returned 404 for the denied original, derived EPUB, cover, conversion job, and conversion request. Reader-session invalidation and private-state filtering passed.
>
> Cleanup completed. Ports `55477`, `18077`, and `4200` are closed; disposable containers, networks, volumes, credentials, fixtures, proxy, and managed files were removed. Final status remains:
>
> ```text
>  M web/yurlib-web/angular.json
> ```
>
> No commit was created. [PR #76](https://github.com/yurtools/yurlib/pull/76) remains open and unmerged.
>
> Remaining blocker: the environment rejected the attempted PR checklist/comment update as an unauthorized remote side effect. No PR metadata changed; its body remains empty.

## Plan

1. Reconcile merged pull request #75, close its process log, update `main`, create the issue #61 branch, and preserve the unrelated Angular analytics preference.
2. Review M2 design sections 13 through 17, ADR-0005 through ADR-0010, all prior M2 acceptance evidence, current Compose storage boundaries, and issue #61 acceptance criteria.
3. Add operator-facing backup and empty-environment restore tooling for PostgreSQL and managed roots with a versioned manifest, hashes, modes, safe paths, and no read-only source bytes.
4. Prove restore rejection for traversal, unexpected or changed files, invalid versions, and incomplete artifacts; require source-root identity revalidation and reconcile interrupted jobs without duplicate publication.
5. Add a complete automated M2 walking-skeleton and cross-user security acceptance suite covering the six formats, curation, personal state, covers, conversion, direct identifiers, jobs, totals, downloads, collections, and session invalidation.
6. Run a disposable backup/restore drill, hostile-input and full repository verification, record benchmark observations without universal claims, and prepare manual desktop/mobile accessibility acceptance instructions.
7. Update operational and acceptance documentation, commit, push, and open a pull request that closes issue #61.
8. Reproduce and repair the failures found during PR #76 manual acceptance: post-merge curation mapping, container staging permissions, missing conversion controls, and missing completed-edition evidence.
9. Add regression coverage, verify the server image's staging permissions as its unprivileged runtime user, run the full repository baseline, push the repair, and request focused acceptance again.

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
- Committed the implementation as `7e90354`, pushed `feat/61-m2-acceptance`, and opened pull request #76 with `Closes #61`.
- Reviewed the failed manual acceptance at `5875924` and reproduced the post-merge title-correction error in a Spring/PostgreSQL integration test. The JDBC record mapper expected `value` and `supersedes_id`, while the query returned `curated_value` and `supersedes_override_id`; both columns now have explicit aliases.
- Added edition identifiers to catalog asset responses and the OpenAPI contract. The Angular personal-state workflow now requires a concrete completed edition, displays the selected edition as evidence, and supports clearing the state.
- Added owner-facing on-demand FB2/MOBI-to-EPUB controls for request, status refresh, cooperative cancellation, safe failure details, and derived EPUB download. Managed-root setup now exposes the existing default-conversion-output setting.
- Updated the server image to run with the deterministic unprivileged UID/GID 10001 and to seed private, writable PDF, cover, and conversion staging directories into a new Compose volume.
- Built the actual server image and ran it with the Compose staging volume as `uid=10001(yurlib)`. Creation and removal of a test file succeeded in all three staging directories. The disposable image, volumes, networks, and bind directories were then removed.
- Committed the acceptance repairs as `6c35f2c`, pushed the branch, and updated pull request #76 without marking visual acceptance complete.
- Recorded the owner retest at exact commit `c9f7f97c020825c4388dca490fb74f592f0f7398`. Desktop 1440×900 and mobile 390×844 passed PDF ingestion and cover generation, fallback behavior, edition-specific read state, favorites, FB2-to-EPUB conversion, merge/undo, post-merge curation, guided-split protection, restricted-reader filtering, overflow, console, and backend/worker-log checks.
- Confirmed restricted assets and work returned 404, session invalidation and private-state filtering passed, and the disposable acceptance environment was completely removed. The only local worktree change remains the preserved user-owned Angular analytics preference.
- Restored pull request #76's empty body, marked its owner desktop/mobile acceptance checklist complete, and retained the `Closes #61` linkage.
- Squash-merged pull request #76 after all automated and owner acceptance checks passed; issue #61 closed through the pull-request linkage.

## Verification

- `python3 -m unittest discover -s ops/tests -p 'test_*.py' -v` — passed, 8 tests.
- Disposable backup/restore drill — passed after the readiness fix; restored server health was `UP` and managed source/restored hashes both equaled `c087dec111f4d337ca6573016e29bf1e95624bc50822af0900bdbc271675454e`.
- `./mvnw -B verify` — passed; server 174 tests with one opt-in benchmark skipped, worker 9 tests, coverage, architecture, formatting, OpenAPI compatibility, PMD, and SpotBugs passed.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages, 0 vulnerabilities. The existing npm install-script policy warning listed four blocked optional/native package scripts.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 18 tests.
- `npm --prefix web/yurlib-web run build` — passed, 380.82 kB initial bundle.
- `docker compose config --quiet` — passed.
- `git diff --check` — passed.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 19 tests including completed-edition evidence and the on-demand conversion UI.
- `npm --prefix web/yurlib-web run build` — passed, 386.72 kB initial bundle.
- `./mvnw -pl services/yurlib-server -Dtest=CatalogRecoveryIntegrationTest,CatalogControllerTest test` — passed, 8 tests. The new recovery path initially reproduced both mapper mismatches before the aliases were corrected.
- Disposable `yurlib_staging_check_76` image/volume permission check — passed for PDF, cover, and conversion staging as UID/GID 10001; cleanup passed.
- Post-repair `./mvnw -B verify` — passed; server 174 tests with one opt-in benchmark skipped, worker 9 tests, coverage, architecture, formatting, backward-compatible OpenAPI diff, PMD, and SpotBugs passed.
- Post-repair `npm --prefix web/yurlib-web ci` — passed; 267 packages and 0 vulnerabilities, with the existing install-script policy warning for four optional/native packages.
- Post-repair `npm --prefix web/yurlib-web test -- --watch=false` — passed, 19 tests.
- Post-repair `npm --prefix web/yurlib-web run build` — passed, 386.72 kB initial bundle.
- Post-repair `docker compose config --quiet` and `git diff --check` — passed.
- GitHub Actions run `37315700375` — passed: Backend, Frontend, Compose Configuration, and Dependency Review.
- Owner desktop/mobile acceptance at `c9f7f97c020825c4388dca490fb74f592f0f7398` — passed in both viewports for every required scenario; cleanup passed.

## Blockers

- None. Pull request #76 is merged and issue #61 is closed.
