# M2 PDF, DOCX, and DjVu Support

- Status: In progress
- Started: 2026-09-30
- Branch: `feat/51-pdf-docx-djvu`
- Issue: [#51](https://github.com/yurtools/yurlib/issues/51)
- Repository: `yurtools/yurlib`

## Prompt

> merged, continue

## Plan

1. Reconcile merged pull request #65 and start issue #51 from merged `main`.
2. Complete the required parser/dependency and isolation ADR before implementation.
3. Add content-detected PDF, DOCX, and DjVu discovery and provenance-preserving bounded metadata outcomes.
4. Integrate all three formats with catalog persistence, search/filtering, progress/errors, authorization, and original download.
5. Add generated multilingual, malformed, encrypted, adversarial, near-limit, over-limit, source-preservation, reprocessing, and resource-evidence fixtures.
6. Run the full verification baseline, update issue acceptance, and open a short-lived pull request.

## Actions and results

- Confirmed pull request #65 merged as `922433dd0a865374660cc917e03372297851b103`; issue #49 is closed and its Project item is Done.
- Created branch `feat/51-pdf-docx-djvu` from merged `origin/main` while preserving the unrelated local Angular analytics preference.
- Moved issue #51 to Project status In Progress.
- Reviewed the accepted M2 resource, isolation, cover, and format boundaries plus current adapter and persistence contracts.
- Reviewed Apache PDFBox 3.0.8 release, security, random-access, dependency, and license guidance. PDFBox supports custom random access, but its security policy excludes hostile-input resource denial of service and recommends sandboxing.
- Proposed ADR-0011: use PDFBox 3.0.8 only in the existing isolated worker; parse selected DOCX OPC/XML and DjVu IFF metadata structures in the server without broad new parser dependencies.
- Owner approved option `1A`; ADR-0011 is Accepted. PDF metadata uses the isolated worker, PDF work remains visibly pending when it is unavailable, and bounded DOCX/DjVu structural metadata stays in the server.
- Added DOCX OPC and DjVu IFF adapters under the shared `bounded-metadata-v4` budget. They preserve selected metadata observations, reject unsafe structures, and do not extract or decode unrelated document content.
- Added PDF discovery and a PostgreSQL-owned metadata queue. The server streams a verified copy into an opaque read-only staging path, immediately catalogs a provisional `PENDING` PDF, and exposes leased input only through an authenticated internal API.
- Added the optional `yurlib-document-worker` module with PDFBox 3.0.8. Each parse runs in a disposable bounded child JVM; the container has no database credentials, source mount, or egress network.
- Added `metadata_state` to the catalog API and UI, repeated format filters for all six formats, safe PDF queue states, and media types for authorized PDF, DOCX, and DjVu original downloads.
- Added generated multilingual, conflicting, malformed, encrypted, macro, external-relationship, sparse, near-limit, and over-limit fixtures. No private documents were committed.
- Added `m2-pdf-docx-djvu-evidence.md`, updated the M2 design and adapter review, retained third-party notices, and documented optional worker startup.

## Verification

- Targeted server adapter/controller tests passed (32 tests before final media-type coverage).
- PostgreSQL queue and server integration tests passed (19 tests).
- Isolated worker tests passed (3 tests), including multilingual Info/XMP conflicts, encrypted/malformed input, a 4 GiB+ sparse-size rejection, and selected-value boundaries.
- The Angular catalog/filter suite passed (12 tests).
- Server PMD and SpotBugs passed after correcting reported findings; worker `verify`, including PMD and SpotBugs, passed.
- `docker compose config` and `docker compose --profile worker config` passed.
- `./mvnw verify` passed cleanly: 124 server tests and 3 isolated-worker tests, coverage, six Flyway migrations, OpenAPI compatibility, PMD, and SpotBugs.
- `npm --prefix web/yurlib-web ci`, `test -- --watch=false`, and `run build` passed; 12 Angular tests passed and the production bundle completed.
- `docker compose config` and `docker compose --profile worker config` passed. Both `server` and `document-worker` images built successfully from clean container contexts.
- `git diff --check` passed, and the unrelated local Angular analytics preference remained excluded from this work.

## Blockers

- None. Pull-request publication remains in progress.
