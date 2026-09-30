# M2 Canonical Catalog and Provenance Schema

- Status: Pull request open
- Started: 2026-09-30
- Branch: `feat/53-canonical-catalog-schema`
- Pull request: [#63](https://github.com/yurtools/yurlib/pull/63)
- Implementation commit: `4041a44`
- Issue: [#53](https://github.com/yurtools/yurlib/issues/53)
- Repository: `yurtools/yurlib`

## Prompt

> merged. continue

## Plan

1. Reconcile merged design PR #62 and mark ADR-0005 through ADR-0010 accepted.
2. Upgrade the M1 Work → Edition → Asset schema without losing catalog records or source hashes.
3. Add content kinds, all six M2 formats, exact-byte identity, derived-asset lineage, contributors, aliases, roles, typed identifiers, redirects, audit events, and optimistic versions.
4. Make raw observation batches append-only and explicitly link them to their source Asset, root, parser, parser version, and extraction version.
5. Add versioned normalized facts, resolved values, curated overrides, and deterministic display precedence with explicit absence/conflict states.
6. Verify fresh migration, M1 upgrade, failed-migration rollback, reprocessing, immutability, constraints, and source preservation on PostgreSQL Testcontainers.

## Actions and results

- Confirmed PR #62 merged as `8438dbb` and moved issue #53 to Project status In Progress.
- Created branch `feat/53-canonical-catalog-schema` from merged `origin/main` while preserving the unrelated local Angular analytics preference.
- Added Flyway migration V4 and expanded the domain model for the canonical catalog and provenance states.
- Changed catalog reprocessing from destructive observation replacement to append-only, source-linked observation sets.
- Added deterministic catalog-display precedence (`CURATED` → `RESOLVED` → `OBSERVED`) with explicit present, absent, and conflict states.
- Added database enforcement for exact-hash Asset identity, mandatory derived-Asset lineage, and append-only observations, normalized facts, and audit events.
- Backfilled each M1 contributor as an unresolved identity scoped to its Work/name evidence, avoiding automatic identity merges based only on equal names.
- Closed approved design issue #41, checked its acceptance criteria, and moved it to Project Done.

## Verification

- Targeted domain, migration, and PostgreSQL integration tests passed.
- M1 → M2 migration preserves rows, source hashes, observations, contributors, and identifiers; an injected migration failure rolls back all V4 changes and leaves schema version 3 intact.
- Full `./mvnw -B verify` passed 100 tests plus formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs.
- `docker compose config` passed.
- `git diff --check` passed.
- Frontend tests/build were not run because no frontend source was changed; the unrelated local Angular analytics preference remains untouched.
- Committed the implementation as `4041a44`, pushed the short-lived branch, and opened pull request #63 against protected `main`.
