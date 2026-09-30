# M2 Persisted Users and Root Authorization

- Status: In progress
- Started: 2026-09-30
- Branch: `feat/54-persisted-users-root-authorization`
- Issue: [#54](https://github.com/yurtools/yurlib/issues/54)
- Repository: `yurtools/yurlib`

## Prompt

> merged, comtinue

## Plan

1. Reconcile merged canonical-schema pull request #63 and start issue #54 from merged `main`.
2. Add persisted users, owner bootstrap and recovery, independent audited capabilities, and immediate stale-session invalidation.
3. Replace the singleton root with multiple `READ_ONLY_SOURCE` and `MANAGED_OUTPUT` roots while retaining alias, identity, containment, and source-immutability controls.
4. Add owner administration and capability-scoped source-management APIs.
5. Enforce per-user root denies across catalog, assets, observations, provenance, downloads, jobs, and personal-state mutation surfaces.
6. Prove two-user non-disclosure and owner recovery behavior through API and integration tests, then run the full verification baseline.

## Actions and results

- Confirmed pull request #63 merged as `4981269b31025e06469d2d6c8b3b2d2c1a8098b8` and issue #53 closed.
- Created branch `feat/54-persisted-users-root-authorization` from merged `origin/main` while preserving the unrelated local Angular analytics preference.
- Added Flyway V5 with persisted users, normalized unique usernames, an enforced single owner, independent capabilities, per-user root denies, authorization versions, and append-only security audit events.
- Added first-start owner bootstrap from deployment credentials. Existing database credentials remain authoritative on later starts, and an explicit non-web recovery command resets the persisted owner credential.
- Added owner-only user administration for account creation, enable/disable, credential resets, capability replacement, and root-deny changes.
- Added request-time authorization-version checks so credential changes, capability reductions, account disablement, and new root denies invalidate existing sessions immediately.
- Replaced the singleton root with multiple `READ_ONLY_SOURCE` and `MANAGED_OUTPUT` roots. Configuration still enforces advertised mount aliases, canonical containment, identity tokens, non-overlap, and no scans of managed-output roots.
- Projected root access into catalog search/counts, mixed-root Asset and observation responses, derived-Asset lineage, downloads, and scan jobs. Denied-only records resolve as not found rather than disclosing their existence.
- Updated the Angular workbench so all authenticated users can browse the catalog while source-management controls and requests require `MANAGE_INGESTION_SOURCES`.
- Updated the active and approved OpenAPI baseline together for the accepted M2 contract changes, including persisted-session fields, administration routes, multiple root modes, and all six M2 formats.
- Added PostgreSQL/MockMvc acceptance coverage for owner bootstrap, normalized reader creation, default visibility, explicit deny, mixed-root filtering, denied downloads/jobs, stale-session invalidation, independent capability grants, audit events, and owner recovery.
- Renamed log 0022 to its merged lifecycle state and recorded pull request #63's squash merge commit.

## Verification

- `./mvnw -B spotless:apply && ./mvnw -B verify` passed: 101 tests, JaCoCo coverage checks, PMD, SpotBugs, formatting, and OpenAPI contract checks.
- `npm --prefix web/yurlib-web ci` passed with zero reported vulnerabilities.
- `npm --prefix web/yurlib-web test -- --watch=false` passed 11 tests, including the reader non-disclosure UI acceptance test.
- `npm --prefix web/yurlib-web run build` passed.
- `docker compose config` passed.
- `git diff --check` passed.
- Confirmed the active and baseline OpenAPI documents are byte-identical.
- The unrelated local `web/yurlib-web/angular.json` analytics preference remains unmodified and will not be committed.
