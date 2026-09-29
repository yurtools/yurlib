# Catalog Reconciliation and Search

- Status: Committed
- Started: 2026-09-29
- Branch: `feature/24-catalog-reconciliation-search`
- Issue: [#24](https://github.com/yurtools/yurlib/issues/24)
- Repository: `yurtools/yurlib`

## Prompt

> continue

## Plan

1. Confirm the metadata-adapter pull request is merged and synchronize from protected `main`.
2. Move issue #24 to `In Progress` and create a short-lived feature branch.
3. Review the accepted catalog model, scan/extraction boundaries, migration constraints, and test infrastructure.
4. Add an application-level candidate reconciliation boundary that skips unchanged files before parsing.
5. Implement transactional PostgreSQL reconciliation for Work, Edition, Asset, Asset Location, and Metadata Observation records.
6. Preserve availability on failed or partial scans and mark unseen locations missing only after complete verified scans.
7. Add bounded, pageable catalog search by title, contributor observation, filename, and identifier.
8. Prove idempotency, extraction-version invalidation, availability behavior, and search with focused unit and PostgreSQL integration tests.
9. Run the full repository verification baseline, commit, push, and open a pull request linked to issue #24.

## Execution log

### 2026-09-29 — Preflight

- Confirmed pull request #34 was squash-merged as `1bd88cb`; issue #16 is closed and its Project item is `Done`.
- Synchronized local `main` with `origin/main` and created `feature/24-catalog-reconciliation-search`.
- Moved issue #24 from `Todo` to `In Progress` in the Yurlib Engineering project.
- Read the project Java/Spring and Spring Boot testing skill instructions and their relevant architecture, testing, JDBC, and Testcontainers guidance.
- Reviewed the accepted vertical-slice architecture, current PostgreSQL schema, durable scan worker, filesystem discovery, bounded metadata extractor, domain types, and existing integration-test setup.

### 2026-09-29 — Implementation

- Extended discovery candidates with their contained path and cheap NIO file facts so unchanged checks occur before metadata parsing.
- Made the bounded metadata adapter publish its extraction-version identifier and propagated that version into each durable scan job.
- Added framework-independent catalog reconciliation, store, and query ports plus immutable candidate outcomes and catalog result records.
- Added the application reconciler that skips matching path/size/modified-time/extraction-version facts without invoking a parser, reparses when the extraction version changes, and keeps an existing location seen when extraction fails.
- Integrated candidate reconciliation into the durable scan worker so processed, skipped, deferred, and failed file outcomes update durable counters.
- Implemented the JDBC catalog adapter. Each candidate locks its location and writes Work, Edition, Asset, Asset Location, and Metadata Observation state in one transaction.
- Parser-only reprocessing preserves Work, Edition, Asset, and Location identities while deterministically replacing file observations. Changed binary hints create a new provisional catalog chain and repoint the unique location without deleting history.
- Implemented complete-scan reconciliation that marks unseen locations `MISSING` without deleting catalog records. Failed and partial scans never invoke this operation.
- Implemented bounded catalog search with a 200-character query limit, page sizes from 1 through 100, deterministic pagination, and matching across title, contributor observation, normalized filename, and identifier key/value.

### 2026-09-29 — Test findings and corrections

- The first compile identified an unqualified nested discovery-candidate type in the worker; it now uses the explicit application type.
- The first PostgreSQL test run showed Spring repository exception translation wrapping input-validation exceptions. The JDBC adapter now uses the component stereotype while `JdbcClient` continues to translate database failures.
- The first full quality gate identified an exposed mutable JSON mapper. The adapter now retains a rebuilt private mapper instance.
- A blank-query pagination test exposed PostgreSQL's ambiguous type handling for a repeated `NULL` search parameter. Blank searches now use an escaped match-all pattern and retain one typed prepared-query shape.
- Added three application unit scenarios for parser-free unchanged skips, extraction-version invalidation, and availability preservation after extraction failure.
- Added PostgreSQL integration coverage for transactional multi-table reconciliation, identity-preserving parser reprocessing, non-destructive missing reconciliation, all four required search dimensions, input bounds, and deterministic pagination.

## Verification

- `./mvnw -B -pl services/yurlib-server -am spotless:apply test -Dtest=DefaultCatalogCandidateReconcilerTest,DefaultScanJobServiceTest,ScanJobWorkerTest,FilesystemScanDiscoveryTest,YurlibServerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` — passed after correcting the initial compile and validation-translation findings.
- `./mvnw -B verify` — passed with 56 tests; formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs gates passed after the defensive mapper correction.
- `npm --prefix web/yurlib-web ci` — passed.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 3 tests.
- `npm --prefix web/yurlib-web run build` — passed.
- `docker compose config` — passed.

## Result

Implementation and local verification are complete in commit `2e38743` (`feat: add catalog reconciliation and search (#24)`). Push, pull-request, and remote-check references are pending.
