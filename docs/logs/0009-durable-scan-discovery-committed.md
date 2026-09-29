# Durable Scan Jobs and Bounded Discovery

- Status: Committed
- Started: 2026-09-29
- Branch: `feature/15-durable-scan-discovery`
- Issue: [#15](https://github.com/yurtools/yurlib/issues/15)
- Repository: `yurtools/yurlib`

## Prompt

> continue with development

## Continuation prompt

> continue with the next task

## Plan

1. Reconcile the accepted scan lifecycle and recovery rules with the existing schema and API contract.
2. Add framework-independent scan use cases and filesystem discovery ports while preserving the domain/application boundaries.
3. Implement transactional queueing, database-visible claims, leases, heartbeats, durable counters, and expired-job recovery without a broker.
4. Walk only the verified configured root without following symlinks; emit normalized contained candidate paths and isolate per-file failures.
5. Gate missing-location reconciliation on verified identity and complete traversal.
6. Add API, persistence, concurrency, restart-recovery, containment, failure-isolation, and progress tests.
7. Run the full verification baseline, commit, push, open a pull request linked to issue #15, and pass protected-branch checks.

## Execution log

### 2026-09-29 — Preflight

- Completed prerequisite issue #23 through pull request #30 and synchronized `main` at `7d19e63`.
- Confirmed issue #15 is open, both dependencies are complete, and moved it to `In Progress` in Yurlib Engineering.
- Created `feature/15-durable-scan-discovery` from synchronized `main`.
- Continued using the project Spring Boot and testing guidance for this backend slice.

### 2026-09-29 — Design reconciliation

- Confirmed Flyway V2 already provides scan states, timestamps, heartbeat, durable counters, completion coverage, file outcomes, and the partial unique index for one active scan per root.
- Kept the design migration-free unless implementation tests prove another persisted field is required.
- Selected an atomic PostgreSQL claim using row locking, with expired `RUNNING` jobs returned to the claimable lifecycle from their persisted heartbeat.
- Kept the worker single-threaded and bounded for M1; no broker, scheduler service, or additional infrastructure is introduced.
- Defined discovery as a filesystem adapter behind an application port. Parser/candidate processing remains an explicit port for issue #16, and catalog missing-location reconciliation remains an explicit port for issue #24.
- Production discovery will not follow directory or file symlinks and will persist only normalized paths relative to the already verified root.
- Reconciliation will be callable only after identity verification and complete traversal; tests will prove that failed or partial discovery cannot invoke it.

### 2026-09-29 — Resumption

- Resumed issue #15 after completing the Maven quality-gate implementation in pull request #32.
- Merged the green quality-gate branch into this feature branch without rewriting the already published branch history.
- Renumbered this active process log from `0008` to `0009` because the quality-gate workflow now owns sequence `0008`.
- The issue #15 pull request will not target `main` until pull request #32 is merged.

### 2026-09-29 — Implementation

- Moved issue #15 to `In Progress` in the Yurlib Engineering GitHub Project.
- Added framework-independent scan-job use cases, the discovery listener boundary, durable job store boundary, bounded worker, and the reconciliation safety boundary.
- Added PostgreSQL queueing, atomic `FOR UPDATE SKIP LOCKED` claims, heartbeat leases, expired-running-job reclaim, per-file outcome upserts, durable counters, completion coverage, and safe job failures.
- Added the scan start and job status endpoints already defined by `contracts/openapi/yurlib-v1.yaml`, including safe Problem Details for missing roots/jobs and active-scan conflicts.
- Added verified-root filesystem discovery for EPUB, FB2, and MOBI candidates. Discovery normalizes relative paths, does not follow symlinks, records per-entry failures, and continues after isolated failures.
- Enabled a single bounded scheduled worker with configurable polling and lease durations. The worker is disabled in database integration tests to prevent scheduler races.
- Reused the existing Flyway V2 scan schema; no database migration or new infrastructure component was required.

### 2026-09-29 — Test findings and corrections

- The initial focused run exposed PostgreSQL JDBC's inability to infer a SQL type for `Instant`; the persistence adapter now binds explicit JDBC timestamps.
- Corrected test package placement so architecture rules inspect production application packages without treating tests as application code.
- Corrected Mockito matcher usage and the API failure-path assertion.
- The first full `mvn verify` run found two SpotBugs defensive-copy findings in `ScanJobResponse`; the response now copies the failure list in its canonical constructor.
- Added unit tests for queue validation, bounded failure retrieval, no-work behavior, failure isolation, reconciliation gating, and safe terminal failures.
- Added filesystem tests for supported extensions, normalized nested paths, ignored unsupported files, symlink rejection, and heartbeat activity.
- Added MVC tests for `202 QUEUED`, durable counters/failures, correlation IDs, and active-scan Problem Details.
- Added PostgreSQL Testcontainers coverage for queue/claim/outcome/completion persistence, the one-active-job invariant, and expired-running-job reclaim.

## Verification

- `./mvnw -B -pl services/yurlib-server -am spotless:apply test -Dtest='DefaultScanJobServiceTest,ScanJobWorkerTest,ScanJobControllerTest,FilesystemScanDiscoveryTest,ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false` — passed, 13 tests.
- `./mvnw -B -pl services/yurlib-server -Dtest=YurlibServerIntegrationTest test` — passed, 8 PostgreSQL-backed tests.
- `./mvnw -B verify` — passed, 42 tests; formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs gates passed.
- `npm --prefix web/yurlib-web ci` — passed; 0 vulnerabilities reported. npm reported the existing blocked-install-script notices.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 3 tests.
- `npm --prefix web/yurlib-web run build` — passed.
- `docker compose config` — passed.

## Result

Implementation and local verification are complete in commit `47dd487` (`feat: add durable scan discovery (#15)`). Pull request creation remains intentionally blocked until the green prerequisite pull request #32 is squash-merged into `main`.
