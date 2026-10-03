# M2 Isolated Conversion Worker and EPUB Routes

- Status: PR Open
- Started: 2026-10-02
- Branch: `feat/60-isolated-conversion-worker`
- Issue: [#60](https://github.com/yurtools/yurlib/issues/60)
- Repository: `yurtools/yurlib`

## Prompt

> merged, proceed

## Plan

1. Reconcile merged pull request #74, close its process log, update `main`, create the issue #60 branch, and preserve the unrelated Angular analytics preference.
2. Review M2 design section 10, ADR-0007 through ADR-0009, managed-root and derived-Asset contracts, current worker isolation, authorization projections, and build/deployment conventions.
3. Complete and record the required dependency and license review for pinned FB2 → EPUB and MOBI → EPUB conversion tooling before implementation.
4. Define versioned route contracts, durable PostgreSQL job state, job-key idempotency, claims, leases, heartbeats, retries, cancellation, staging, validation, publication, and authorization-safe API behavior.
5. Extend the optional non-root worker with fixed-argument conversion execution, bounded job directories, resource ceilings, no database/source-root access, and no general egress.
6. Implement server-owned staging, output validation, atomic managed-root publication, canonical derived-Asset lineage, and authorized download behavior without modifying originals.
7. Add focused unit, HTTP-slice, PostgreSQL integration, container, failure-injection, authorization, contract, and representative fixture tests for both approved routes.
8. Run the relevant verification baseline, document operational and security evidence, commit, push, and open a pull request that closes issue #60.

## Actions and results

- Confirmed pull request #74 was squash-merged as `1a336b12c6587a3beea9b3b36b255aca5a0431cd`; issue #59 is closed and its Project item is Done.
- Fast-forwarded local `main`, created `feat/60-isolated-conversion-worker`, and preserved the unrelated user-owned `web/yurlib-web/angular.json` analytics preference.
- Reviewed M2 design section 10, ADR-0007 through ADR-0009, existing managed-root and derived-Asset contracts, worker isolation, authorization projections, Compose boundaries, and issue #60 acceptance criteria.
- Selected and documented calibre 9.15.0 (`GPL-3.0-only`) from the official release. Verified the publisher SHA-512 values for the x86-64 and ARM64 Linux artifacts and recorded the route and packaging review in `docs/architecture/m2-conversion-dependency-review.md`.
- Added Flyway V12 with a durable PostgreSQL conversion queue, one default conversion-output root, route/version/settings/source-hash keys, optimistic versions, claims, leases, heartbeats, bounded retries, cooperative cancellation, and failed-safe terminal states. Existing cover defaults migrate to the conversion default so an installed managed root remains usable.
- Added authorization-checked source staging, 512 MiB input and 1 GiB output limits, streamed SHA-256 verification, bounded EPUB archive validation, atomic same-filesystem publication, canonical derived Asset/location/lineage persistence, and cleanup of cancelled, failed, expired, and reclaimed attempt files. Originals remain read-only.
- Added public request/status/cancel contracts and authenticated internal claim/input/heartbeat/output/result contracts. Duplicate requests use `ON CONFLICT` idempotency, and inaccessible existing jobs are not returned.
- Extended the optional document worker with only `FB2_TO_EPUB_V1` and `MOBI_TO_EPUB_V1`, fixed calibre executable and arguments, exact route-contract validation, per-job calibre configuration/temp directories, deadline enforcement, cancellation heartbeats, hash-declared upload, and safe timeout/resource/rejection classifications.
- Updated derived download, catalog projection, and curation authorization to walk the complete transitive source lineage in addition to checking the output root. The acceptance fixture covers both direct and second-generation derivatives of a denied source.
- Built the worker image. The first route run exposed missing Qt runtime libraries and publisher-tree ownership requirements; pinned `libxkbcommon0` and `libglx0` and copied calibre as the non-root worker user. The rebuilt image ran as UID/GID 10001 and reported `ebook-convert (calibre 9.15.0)`.
- Ran the English, Cyrillic, and Japanese authored FB2 fixtures through the fixed route and ran a generated representative MOBI through the fixed MOBI route. ZIP structure, first/stored EPUB mimetype, readable metadata, and unchanged source hashes passed. Removed all disposable route artifacts.
- Added focused controller, route-contract, hostile EPUB, PostgreSQL queue/publication/idempotency/cancellation/retry/restart/failure, schema, and transitive-authorization tests. Updated the OpenAPI contract, README, development environment, third-party notices, and dependency evidence.

## Verification

- `./mvnw -pl services/yurlib-document-worker -Dtest=ConversionRouteContractTest test` — passed, 2 tests.
- `./mvnw -pl services/yurlib-server -Dtest=ConversionQueueIntegrationTest,ConversionControllerTest,EpubConversionValidatorTest test` — passed, 13 tests.
- `docker build --file services/yurlib-document-worker/Dockerfile --tag yurlib-document-worker:issue-60 .` — passed after adding the required pinned Qt runtime libraries.
- Non-root/version check — passed as UID/GID 10001 with calibre 9.15.0.
- Representative route corpus — passed for three FB2 fixtures and one generated MOBI fixture under the worker resource and no-egress controls.
- `./mvnw verify` — server module passed 174 tests with one benchmark skipped, coverage, PMD, SpotBugs, and the OpenAPI compatibility gate. The worker module passed all 9 tests; its first static-analysis pass identified conservative `Path.getParent()` nullability warnings, which were corrected before resuming the reactor.
- `./mvnw verify -rf :yurlib-document-worker` — passed all 9 worker tests, PMD, and SpotBugs after the nullability correction.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages installed, zero vulnerabilities. The existing four blocked optional install-script notices remain unchanged.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 18 tests in 2 files.
- `npm --prefix web/yurlib-web run build` — passed; initial bundle 380.82 kB raw and 97.10 kB estimated transfer.
- `docker compose config` and `docker compose --profile worker config` — passed.
- `git diff --check` — passed.
- Committed the implementation as `f57364f` (`feat: add isolated conversion worker (#60)`) and pushed `feat/60-isolated-conversion-worker`.
- Opened pull request [#75](https://github.com/yurtools/yurlib/pull/75), which closes issue #60 after squash merge.

## Blockers

- None.
