# M2 Managed Covers and Representative Images

- Status: In progress
- Started: 2026-10-01
- Branch: `feat/57-managed-covers`
- Issue: [#57](https://github.com/yurtools/yurlib/issues/57)
- Repository: `yurtools/yurlib`

## Prompt

> merged proceed

## Plan

1. Reconcile merged pull request #71, close its process log, and start issue #57 from merged `main` while preserving the unrelated Angular analytics preference.
2. Review ADR-0008 through ADR-0010, M2 design sections 7 and 9, the existing typed-root, lineage-authorization, bounded-task, worker, API, and Angular seams, and renderer dependency/license options.
3. Add durable cover state, provenance, job leasing, output-root selection, and complete source-lineage authorization with a Flyway migration and application/domain ports.
4. Stage immutable source input, process only approved cover candidates under encoded, dimension, pixel, allocation, deadline, and active-content bounds, validate normalized output, and publish through opaque same-filesystem paths with atomic rename.
5. Support selection in the approved order: curated source choice, declared EPUB/FB2/MOBI cover, DOCX package thumbnail, isolated PDF/DjVu page-one representative, then format fallback. A cover failure remains independent of catalog ingestion.
6. Add authorization-aware immutable cover delivery and integrate accessible catalog thumbnails and fallback states without exposing untrusted source bytes.
7. Add generated hostile and valid fixtures, unit/slice/PostgreSQL integration/worker/UI coverage, update OpenAPI and architecture evidence, run the full repository baseline, and open a pull request.

## Frontend direction

- Visual thesis: treat each catalog row as a restrained editorial shelf entry, with the normalized cover as the single visual anchor and the existing warm-paper surfaces left intact.
- Content plan: thumbnail or format fallback, existing title/contributor facts, file actions, then curation; no new dashboard or ornamental panel.
- Interaction thesis: a short image reveal after load, a subtle cover lift on row focus/hover, and clear state transitions that respect reduced-motion preferences.

## Actions and results

- Confirmed pull request #71 was squash-merged as `eab7f79`; issue #69 is closed and its Project item is Done.
- Fast-forwarded local `main`, created `feat/57-managed-covers`, preserved the unrelated local Angular analytics preference, and moved issue #57 to Project status In Progress.
- Reviewed accepted ADR-0008 through ADR-0010 and the approved M2 cover/storage design.
- Selected the existing optional isolated document worker as the rendering boundary; no new service, broker, database, or framework is introduced.
- Added schema version 9 with independent cover jobs, immutable derivative provenance, one selectable managed-output root, and optimistic per-work cover preferences.
- Extended root configuration through the domain, API, OpenAPI contract, persistence, and setup UI so only a `MANAGED_OUTPUT` root can be the default cover destination.
- Added bounded source staging with pre/post file identity checks, digest verification, read-only staged inputs, leases, retries, and cover failures that do not fail catalog ingestion.
- Added worker extraction for declared EPUB, FB2, and MOBI covers, DOCX package thumbnails, PDF page one, and DjVu page one. Extraction rejects unsafe archive paths and active DOCX content, disables external XML entities, limits encoded input and decoded allocations, and executes each job in a bounded child JVM.
- Added output validation and normalization to JPEG or PNG with metadata removed and hard limits of 1600 by 2400 pixels, 8 megapixels, and 16 MiB encoded output.
- Added same-filesystem staging and atomic publication under opaque content-addressed paths, while preserving every original source asset.
- Added complete recursive source-lineage authorization for delivery, private immutable caching with an ETag, and owner curation of the preferred source cover.
- Added catalog cover availability, accessible lazy-loaded thumbnails, title-initial fallback behavior, responsive styling, and reduced-motion support.
- Updated Compose and README setup for the writable managed root and cover staging limits; updated the worker third-party notice for DjVuLibre.
- Added generated valid and hostile extraction fixtures plus worker, controller, Angular, PostgreSQL integration, migration, API-contract, and authorization regression coverage.
- The initial worker image build exposed an obsolete `djvulibre=3.5.28-r5` pin against the current Alpine base. Updated it to `3.5.30-r0`, confirmed the package contains `ddjvu`, and rebuilt the image successfully.

## Verification

- `./mvnw verify` — passed; server: 145 tests with 1 benchmark skipped, worker: 7 tests; Spotless, API compatibility, JaCoCo, PMD, and SpotBugs passed.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages installed, 0 vulnerabilities. npm reported the existing blocked optional install-script warning for four packages.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed; 14 tests.
- `npm --prefix web/yurlib-web run build` — passed; production bundle generated.
- `docker compose config` — passed.
- `docker compose build document-worker` — passed with DjVuLibre 3.5.30-r0 installed.
- `docker run --rm --entrypoint sh yurlib-document-worker -c 'ddjvu --help ...'` — passed; the runtime contains an executable `ddjvu`.
- `git diff --check` — passed.
- Manual desktop and mobile visual acceptance remains for the pull request; automated responsive, fallback, accessibility, and build checks pass.

## Blockers

- None.
