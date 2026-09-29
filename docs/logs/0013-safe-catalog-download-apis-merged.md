# Safe Catalog and Download APIs

- Status: Merged
- Started: 2026-09-29
- Branch: `feature/25-safe-catalog-download-apis`
- Issue: [#25](https://github.com/yurtools/yurlib/issues/25)
- Repository: `yurtools/yurlib`

## Prompt

> merged. continue

## Plan

1. Confirm documentation pull request #37 completion, synchronize protected `main`, and close its process-log lifecycle.
2. Move issue #25 to `In Progress` and create a short-lived feature branch.
3. Reconcile the approved OpenAPI contract with the existing root, scan, job, catalog, persistence, containment, and Problem Details boundaries.
4. Add thin catalog and original-download controllers with immutable API records.
5. Resolve downloads by asset ID only, then revalidate root identity, location availability, containment, current file facts, and a safe response filename before streaming.
6. Ensure expected failures use RFC 9457 Problem Details with stable codes and correlation IDs.
7. Add focused application, filesystem, MVC contract, and PostgreSQL integration tests for success and failure behavior.
8. Run the full repository verification baseline, commit, push, and open a pull request linked to issue #25.

## Execution log

### 2026-09-29 — Preflight

- Confirmed pull request #37 was squash-merged as `74c9647`; issue #36 is closed and its project item is `Done`.
- Synchronized local `main`, created `feature/25-safe-catalog-download-apis`, and moved issue #25 from `Todo` to `In Progress`.
- Read the project Java/Spring and Spring Boot testing skill instructions plus their exception-handling and MVC slice-test guidance.
- Reviewed the complete OpenAPI contract, accepted vertical-slice API and observability rules, current controllers, correlation filter, Problem Details handler, catalog query port, root verifier, and persistence schema.

### 2026-09-29 — Implementation

- Added the catalog HTTP endpoint and immutable response records for the existing bounded `CatalogQuery` application port.
- Added an asset-content application service that resolves downloads only by opaque asset UUID, rejects missing or unavailable locations, loads the owning root, and delegates filesystem access through a port.
- Added a JDBC asset-content lookup that joins catalog assets to their stored locations without accepting a client-provided path.
- Added a filesystem opener that revalidates the configured root identity, normalized containment, every symbolic-link component, canonical containment, regular-file status, size, persisted modification time, and optional file key before opening a no-follow streaming handle.
- Added media-type selection and bounded `Content-Disposition` filename sanitization for EPUB, FB2, and MOBI downloads.
- Extended scoped Problem Details handling with stable asset failure codes, method validation, invalid UUID handling, and correlation identifiers.
- Added application-service, MVC, filesystem security, and PostgreSQL mapping tests. The filesystem regression suite covers final and intermediate symbolic links, changed file facts, and changed root identity.
- Reviewed the completed implementation after the first green build and tightened intermediate-symlink handling before final verification.

### 2026-09-29 — Delivery

- Committed the implementation as `f9f152c` (`feat: complete safe catalog and download APIs (#25)`).
- Pushed `feature/25-safe-catalog-download-apis` to `origin`.
- Opened pull request [#38](https://github.com/yurtools/yurlib/pull/38), linked to close issue #25 after merge.

### 2026-09-29 — Merge reconciliation

- Confirmed pull request #38 was squash-merged to protected `main` as `1f143d8`.
- Confirmed issue #25 closed automatically and its Yurlib Engineering project item moved to `Done`.

## Verification

- `./mvnw verify` — passed; 70 tests, OpenAPI compatibility, architecture rules, coverage thresholds, PMD, SpotBugs, and FindSecBugs all passed. PostgreSQL integration tests ran with Testcontainers.
- `npm --prefix web/yurlib-web ci` — passed; 268 packages audited with zero vulnerabilities.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed; 3 tests.
- `npm --prefix web/yurlib-web run build` — passed.
- `docker compose config` — passed.
- `git diff --check` — passed.
- GitHub Actions run [36629232093](https://github.com/yurtools/yurlib/actions/runs/36629232093) — passed all backend, frontend, dependency-review, and Compose jobs for pull request #38.
- An earlier full Maven run found two PMD literal-comparison findings; both were corrected before the final successful run.

## Result

Pull request #38 was squash-merged as `1f143d8`; issue #25 is closed and its project item is `Done`.
