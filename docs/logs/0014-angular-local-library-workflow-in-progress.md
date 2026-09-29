# Angular Local-Library Workflow

- Status: In progress
- Started: 2026-09-29
- Branch: `feature/26-angular-local-library-workflow`
- Issue: [#26](https://github.com/yurtools/yurlib/issues/26)
- Repository: `yurtools/yurlib`

## Prompt

> merged. remember to update logs file names for previous steps. continue

## Plan

1. Confirm pull request #38 and issue #25 completion, synchronize protected `main`, and rename every remaining `pr-open` process log to its merged lifecycle.
2. Select issue #26 from the accepted M1 implementation order and move its GitHub Project item to `In Progress`.
3. Reconcile the Angular 22 shell, OpenAPI contract, and section 10 workflow requirements, including the server-advertised mount-alias gap.
4. Add a relative-URL API client, typed contract models, signal-based workflow state, accessible Signal Forms, bounded scan polling, searchable/pageable catalog results, and asset-ID downloads.
5. Build a restrained responsive library workbench and verify desktop/mobile rendering plus keyboard and reduced-motion behavior.
6. Add focused HTTP, state, component, polling-cleanup, validation, failure, catalog, and download tests.
7. Run the full repository verification baseline, commit, push, and open a pull request linked to issue #26.

## Execution log

### 2026-09-29 — Preflight

- Confirmed pull request #38 was squash-merged as `1f143d8`; issue #25 is closed and its project item is `Done`.
- Confirmed `0013-safe-catalog-download-apis-pr-open.md` was the only remaining `pr-open` process log and changed its lifecycle to `merged`.
- Synchronized local `main` and created `feature/26-angular-local-library-workflow`.
- Confirmed issue #26 is next in the accepted M1 order before final owner-access and acceptance issue #22.
- Read the project-local `angular-developer` and `frontend-skill` instructions plus the required Angular components, naming, signals, HTTP, Signal Forms, dependency injection, styling, and testing references.
- Reviewed Angular 22.2 project configuration, the current shell, OpenAPI schemas, and local-library workflow requirements.
- Identified that root setup cannot satisfy “only server-advertised mount aliases” with the current contract because no endpoint exposes configured aliases before a root exists. A backward-compatible alias-list operation is required without exposing physical paths.

### 2026-09-29 — Implementation

- Added `GET /api/v1/library-mounts` to the OpenAPI contract and Spring Boot server. The endpoint returns sorted deployment-defined aliases and does not expose physical paths.
- Added a typed Angular API service using relative `/api` and `/actuator` URLs, plus a development proxy for the Spring Boot server.
- Replaced the starter shell with the accessible root-setup, scan-status/failure, catalog-search, pagination, and asset-download workflow.
- Used Angular signals and Signal Forms for local state and validation. Scan polling starts from the server-returned `QUEUED` job, uses bounded backoff, and stops for terminal states or component destruction.
- Added responsive workbench styling with visible focus states and reduced-motion handling. Kept the component stylesheet below its build budget by placing shared workbench rules in the global stylesheet bundle.
- Added backend endpoint/registry/contract tests and frontend API/component tests for relative URLs, validation, polling cleanup, honest progress, safe failures, provisional values, search, pagination, and asset-ID downloads.
- Updated the vertical-slice architecture document to record mount-alias discovery as part of the API and Angular workflow.
- Built and started the real Spring Boot/PostgreSQL Compose stack on temporary workstation ports because ports 5432 and 8080 were already occupied. Started the Angular development server against that backend and removed the temporary proxy override afterward.
- Confirmed through the Angular development proxy that the application shell loads, the server advertises only `main`, the empty catalog response matches the contract, and health is `UP`.
- Attempted the required rendered desktop/mobile inspection through the browser-control skill. No browser instance was connected to this session, so rendered visual inspection remains a documented manual verification item rather than a claimed pass.

## Verification

- `npm --prefix web/yurlib-web run build` — passed; production bundle completed without budget warnings.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 7 tests across 2 files.
- Focused backend tests — passed, 8 tests covering the endpoint, sorted/redacted registry response, and complete OpenAPI path set. The first contract run correctly exposed an outdated exact path-count assertion; the assertion was changed to verify the complete reviewed path set.
- Live proxy smoke check — passed for the shell document, mount list, empty catalog, and backend health.
- Rendered browser check — blocked because no browser instance was connected; desktop/mobile visual inspection remains pending.
- `./mvnw verify` — passed; 72 tests, JaCoCo thresholds, PMD, SpotBugs, formatting, architecture rules, migrations, and the backward-compatible OpenAPI diff all passed.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages installed and 0 vulnerabilities reported.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 7 tests across 2 files.
- `npm --prefix web/yurlib-web run build` — passed; 326.14 kB initial bundle and no budget warnings.
- `docker compose config` — passed.

## Result

Implementation is in progress.
