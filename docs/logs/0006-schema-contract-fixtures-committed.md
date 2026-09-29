# Schema, Contract, and Fixture Foundation

- Status: Committed
- Started: 2026-09-29
- Branch: `feature/20-schema-contract-fixtures`
- Issue: [#20](https://github.com/yurtools/yurlib/issues/20)
- Repository: `yurtools/yurlib`

## Prompt

> continue with development

## Plan

1. Implement a Flyway migration for the eight first-slice persistence records and database-enforced invariants from ADR-0003 and the vertical-slice design.
2. Add framework-independent Java domain records and enums, with architecture tests that prevent Spring MVC and persistence dependencies from entering the domain.
3. Add pinned, deterministic OpenAPI syntax and backward-compatibility checks against a reviewed baseline.
4. Add a generated and openly authored fixture corpus for valid EPUB, FB2, and MOBI inputs plus malformed, XXE, decompression, Unicode-path, traversal, and symlink-escape cases.
5. Provide fixture integrity helpers and tests that prove acceptance work cannot silently mutate the source corpus.
6. Verify the migration from an empty PostgreSQL 18 database with Testcontainers and run the complete repository baseline.
7. Commit, push, open a pull request linked to issue #20, pass protected-branch checks, and update this log with the result.

## Execution log

### 2026-09-29 — Preflight

- Confirmed protected `main` was clean and synchronized at `b33820b`.
- Confirmed issue #20 is open, has no dependencies, and is the first task in the accepted M1 implementation order.
- Read the complete project `java-spring-best-practices` and `spring-boot-testing` skills plus their PostgreSQL Testcontainers references.
- Reviewed ADR-0003, the vertical-slice data model and acceptance matrix, the initial OpenAPI contract, the Maven reactor, CI workflow, and existing server tests.
- Created `feature/20-schema-contract-fixtures` and moved issue #20 to `In Progress` in Yurlib Engineering.
- Selected pinned Swagger Parser and OpenAPI Diff build integrations after checking their primary project documentation.

### 2026-09-29 — Persistence and domain foundation

- Added Flyway migration `V2__create_local_library_schema.sql` for library roots, scan jobs, file outcomes, works, editions, assets, asset locations, and metadata observations.
- Added foreign keys, value checks, normalized-path checks, unique root/location identity, non-negative counters, and a partial unique index that permits only one queued or running scan per root.
- Advanced the repository schema marker to version 2.
- Added framework-independent Java records and enums for each first-slice domain concept with constructor-level invariant checks.
- Strengthened ArchUnit so domain classes cannot depend on Spring, Jakarta Persistence, API, or infrastructure packages.
- Expanded the PostgreSQL Testcontainers test to verify an empty database reaches schema v2, contains all eight tables, enforces the active-job constraint, and rejects a backslash traversal path.

### 2026-09-29 — Contract gate

- Added pinned Swagger Parser `2.1.48` syntax validation for the OpenAPI 3.1 contract.
- Added pinned OpenAPI Diff `2.1.4` verification against `contracts/openapi/baseline/yurlib-v1.yaml`; backward-incompatible changes now fail `mvn verify`.
- Added contract-maintenance instructions that require explicit review before updating the compatibility baseline.

### 2026-09-29 — Fixture corpus

- Added repository-authored FB2 sources for a valid book, a Unicode filename, malformed XML, and an XXE payload.
- Added a reusable fixture generator for minimal EPUB and MOBI files, a traversal-entry EPUB, a bounded high-expansion EPUB, and an escaping symlink.
- Added SHA-256 source snapshots plus post-scenario comparison so acceptance tests can prove that source bytes were not modified.
- Added tests for every required fixture category, signatures/structure, expansion ratio, path escape, and source immutability.

### 2026-09-29 — Test-harness correction

- The first focused test run failed because forked Maven tests did not receive `maven.multiModuleProjectDirectory`, a domain-package test was included in the ArchUnit rule, and the local AssertJ version lacked one path assertion.
- Replaced the Maven-property assumption with repository-root discovery, moved the test out of the production domain package, and used supported path assertions.
- The failures did not affect the migration itself: the first PostgreSQL run had already migrated an empty database successfully to schema v2.

### 2026-09-29 — Delivery

- Committed the issue #20 implementation as `0e1e8b2` (`Establish local-library foundation (#20)`).
- Pushed `feature/20-schema-contract-fixtures` and opened pull request #29, `Establish local-library schema and test foundation`, with `Closes #20`.
- Required CI run `36585622003` passed: `Backend`, `Frontend`, `Compose Configuration`, and `Dependency Review`.

## Verification

- `mvn -B -pl services/yurlib-server test`: 13 tests passed after the final schema and invariant changes.
- `mvn -B verify`: passed; OpenAPI Diff reported equivalent specifications, 13 tests passed, PostgreSQL 18 migrated from empty to v2, and PMD, SpotBugs, and ArchUnit passed.
- `npm --prefix web/yurlib-web ci`: completed with zero vulnerabilities.
- `npm --prefix web/yurlib-web test -- --watch=false`: one test file and three tests passed.
- `npm --prefix web/yurlib-web run build`: production build passed.
- `docker compose config`: passed.
- `cmp contracts/openapi/baseline/yurlib-v1.yaml contracts/openapi/yurlib-v1.yaml`: passed.
- `git diff --check`: passed.
- Pull-request CI run `36585622003`: all four required checks passed.

## Result

All issue #20 implementation and acceptance criteria are complete. The branch is committed, pull request #29 is open, and protected-branch CI is green; squash merge remains.
