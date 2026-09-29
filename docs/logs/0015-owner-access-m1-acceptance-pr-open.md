# Owner Access and M1 Acceptance

- Status: Pull request open
- Started: 2026-09-29
- Branch: `feature/22-owner-access-m1-acceptance`
- Issue: [#22](https://github.com/yurtools/yurlib/issues/22)
- Repository: `yurtools/yurlib`

## Prompt

> merged. proceed

### Architecture approval

> approve ADR-0004

## Plan

1. Confirm pull request #39 and issue #26 completion, synchronize protected `main`, and rename the completed process log to its merged lifecycle.
2. Move issue #22 to `In Progress` and create its short-lived feature branch.
3. Audit ADR-0003, the vertical-slice access and acceptance requirements, the current Spring configuration, observability, fixture corpus, and existing automated scenario coverage.
4. Implement an explicit loopback-only unauthenticated profile and authenticated owner access for every non-loopback deployment without committing credentials.
5. Protect root configuration and scan start with owner authority, integrate the Angular client with the selected owner-access mechanism, and keep safe Problem Details behavior.
6. Close acceptance, security, observability, source-integrity, restart, and reference-measurement evidence gaps with focused automated or documented human checks.
7. Run the full verification baseline, update authoritative documents, commit, push, and open a pull request linked to issue #22.

## Execution log

### 2026-09-29 — Preflight

- Confirmed pull request #39 was squash-merged as `ddd310e`; issue #26 is closed and its project item is `Done`.
- Synchronized local `main`, created `feature/22-owner-access-m1-acceptance`, and moved issue #22 from `Todo` to `In Progress`.
- Confirmed issue #22 is the final child in the accepted M1 implementation order.
- Read the project Spring Boot implementation, Spring Boot testing, and architecture-decision skills.
- Reviewed ADR-0003, the vertical-slice access/observability/acceptance requirements, current dependencies and configuration, existing fixture corpus, integration tests, parser security tests, and process change-control rules.
- Identified authentication as an explicitly deferred architecture decision. The repository currently has no authentication dependency, and project policy requires owner approval before selecting the model or adding a major framework.
- Drafted proposed ADR-0004 recommending Spring Security session-based access for one deployment-defined owner, Angular-compatible CSRF protection, fail-closed shared-network configuration, and a programmatically guarded loopback-only development profile.

### 2026-09-29 — Architecture approval

- The project owner approved ADR-0004.
- Changed ADR-0004 from `Proposed` to `Accepted` and updated the ADR index.
- The accepted decision now authorizes the Spring Security dependency and the session-based, single-owner access model described by ADR-0004.

### 2026-09-29 — Owner access, UI, and acceptance implementation

- Added Spring Security owner mode with deployment-supplied credentials, server-side sessions, Angular-compatible cookie/header CSRF, generic correlated Problem Details, owner-only API and sensitive actuator access, and public health/session discovery.
- Added the explicit `loopback-dev` profile with a loopback bind default and a startup guard that rejects wildcard, non-loopback, blank, and invalid addresses.
- Added the Angular sign-in, sign-out, session-expiry, loading, and loopback-development states. The password is cleared after every sign-in attempt and is not stored.
- Added scan lifecycle telemetry for queue depth, job/file counters, parser failure codes, duration, correlation, and safe lifecycle logs without physical paths or secrets.
- Added a PostgreSQL-backed walking-skeleton acceptance test from root configuration through scan, catalog, original-byte download, and unchanged rescan.
- The walking-skeleton test exposed timestamp precision defects at the PostgreSQL boundary. Changed rescan and download fact comparisons to tolerate the one-microsecond rounding interval that PostgreSQL can represent; the unchanged rescan now processes zero files and skips four.
- Updated the OpenAPI session/CSRF security contract, local development guidance, authoritative architecture documents, and the M1 acceptance report.
- Reviewed Compose exposure and changed the default server and PostgreSQL host bindings to `127.0.0.1`; deliberate non-loopback server exposure remains documented as requiring TLS and secure cookies.
- Started an isolated PostgreSQL 18 container, the owner-mode server, and the Angular development server for browser acceptance. No in-app or connected browser instance was available, so visual acceptance was not claimed and remains on the pull-request checklist.

## Verification

- Focused Spring Security tests pass.
- Focused scan worker and Micrometer telemetry tests pass.
- The PostgreSQL-backed M1 walking-skeleton acceptance test passes.
- Full Maven verification passes: 88 tests with formatting, OpenAPI compatibility, JaCoCo, PMD, and SpotBugs gates green.
- Clean npm install passes with no reported vulnerabilities.
- Angular tests pass: 8 tests.
- Angular production build passes.
- `docker compose config` passes and confirms loopback host bindings.
- Updated owner-access visual acceptance is pending because no browser instance was connected.
- Pull request [#40](https://github.com/yurtools/yurlib/pull/40) is open and linked to close issue #22 after merge.
- Pull request CI passed: backend, frontend, Compose configuration, and dependency review are green.

## Result

Implementation is committed and pushed. Pull request #40 is open with green CI; updated owner-access visual acceptance remains pending.
