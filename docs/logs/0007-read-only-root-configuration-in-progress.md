# Read-Only Root Configuration

- Status: In progress
- Started: 2026-09-29
- Branch: `feature/23-read-only-root-configuration`
- Issue: [#23](https://github.com/yurtools/yurlib/issues/23)
- Repository: `yurtools/yurlib`

## Prompt

> continue with development

## Plan

1. Bind deployment-owned mount aliases to canonical allowed prefixes with validated Spring configuration.
2. Add a filesystem boundary that resolves normalized relative paths, rejects traversal and symlink escapes, and verifies the operator-owned identity marker without writing to the source.
3. Persist only the SHA-256 identity digest through a separate JPA adapter while keeping domain and application code framework-independent.
4. Implement the contracted create/list root API with validation and safe RFC 9457 Problem Details for missing and mismatched markers and configuration conflicts.
5. Add unit, web-slice, persistence, filesystem, Unicode/case, overlap, traversal, symlink, and source-immutability tests.
6. Update the Compose deployment to mount a source library read-only and document local alias configuration.
7. Run the full verification baseline, commit, push, open a pull request linked to issue #23, and pass protected-branch checks.

## Execution log

### 2026-09-29 — Preflight

- Confirmed issue #20 and pull request #29 completed the required schema/contract dependency on `main` at `98297b0`.
- Confirmed issue #23 is the next accepted M1 task and moved it to `In Progress` in Yurlib Engineering.
- Created `feature/23-read-only-root-configuration` from synchronized `main`.
- Reused the already-read project Spring Boot and testing skills for this consecutive backend task.

### 2026-09-29 — Implementation

- Added framework-independent application ports and a root-configuration service that permits one read-only root and persists only the verified identity digest.
- Added deployment configuration that maps lowercase mount aliases to canonical filesystem prefixes and rejects duplicate or overlapping mounts.
- Added containment enforcement for absolute paths, traversal, non-normalized paths, backslashes, and symlinks that resolve outside the configured prefix.
- Added read-only verification of `.yurlib-root-id`, including bounded marker size, regular-file checks, constant-time token comparison, and SHA-256 digest generation.
- Added separate API, application, filesystem, configuration, and JPA persistence adapters; domain and application dependencies are enforced with ArchUnit.
- Added `POST` and `GET /api/v1/library-roots`, safe RFC 9457 Problem Details, and canonical UUID correlation identifiers.
- Added Flyway migration V3 with database-enforced single-root constraints and JPA integration coverage against PostgreSQL 18.
- Added a non-root multi-stage server image and a Compose server service whose operator-owned library bind mount is read-only.
- Updated the local-development instructions, environment example, and development architecture to document the mount alias and identity-marker model.

### 2026-09-29 — Corrections found by verification

- Made the Hibernate JDBC type explicit for the existing `CHAR(64)` digest column after schema validation detected a `VARCHAR` expectation.
- Canonicalized accepted correlation identifiers through `UUID` after SpotBugs rejected direct request-header reflection.
- Changed the PostgreSQL 18 volume target from `/var/lib/postgresql/data` to `/var/lib/postgresql` after an actual Compose startup exposed the image's version-specific data-layout requirement.

## Verification

- `mvn -B verify` — passed; 28 tests, OpenAPI compatibility, PMD, SpotBugs, JaCoCo, packaging, Flyway, Hibernate schema validation, and PostgreSQL Testcontainers.
- `npm --prefix web/yurlib-web ci` — passed; 0 vulnerabilities reported.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed; 3 tests.
- `npm --prefix web/yurlib-web run build` — passed.
- `docker compose config` — passed and rendered `/library/main` with `read_only: true`.
- `docker compose build server` — passed using the Java 25 build and non-root runtime images.
- Runtime smoke test on alternate host ports — PostgreSQL became healthy, the server health endpoint returned `UP`, the root API accepted the configured alias and marker, and the response omitted both token and digest.
- Runtime write test — `touch /library/main/should-not-write` as container root failed with `Read-only file system`.
- `git diff --check` — passed.

## Result

Implementation and local verification complete. Commit, pull request, and protected-branch checks are pending.
