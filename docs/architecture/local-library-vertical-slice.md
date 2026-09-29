# Yurlib First Local-Library Vertical Slice

- Status: Approved for implementation planning
- Date: 2026-09-29
- Parent requirement: PC-03, PC-04, PC-09, PC-10
- Architecture decisions: ADR-0002, ADR-0003
- Planning issue: #3

## 1. Outcome

Configure one read-only library root, start a durable scan, discover plain EPUB/FB2/MOBI files, persist provisional catalog records and extraction failures, display progress and catalog results in Angular, and download an available original asset.

This is the walking skeleton for M1. It proves the complete browser → API → application → filesystem/PostgreSQL → API → browser path before deeper catalog behavior is added.

## 2. Scope

### Included

- one active `READ_ONLY` root per deployment;
- deployment-defined allowed mount aliases;
- root identity-marker validation;
- manual scans;
- plain `.epub`, `.fb2`, and `.mobi` candidates;
- stable-file checks before and after parsing;
- minimal metadata: observed title, contributors, language, identifiers when present, format, size, and modification time;
- provisional Work, Edition, Asset, Asset Location, and Metadata Observation records;
- persisted scan jobs, counters, warnings, and file failures;
- unchanged-file skip behavior and idempotent catalog effects;
- restart recovery through durable jobs and repeatable discovery;
- catalog list/search by title, contributor observation, filename, or identifier;
- original-asset download by asset identifier;
- Angular root setup, scan progress/failures, catalog list, and download action;
- generated/licensed fixtures and malicious-path regression cases.

### Deferred

- `.fb2.zip`, archives, and archive extraction;
- scheduled scans and filesystem notifications;
- multiple simultaneously active roots;
- full-content hashing and exact duplicate classification;
- curated metadata editing, contributor merging, tags, collections, conversion, connectors, and AI;
- WebSockets or server-sent events;
- filesystem reorganization or writes to source roots;
- non-loopback deployment without owner authentication.

## 3. Quality priorities

In order:

1. Preserve source files and path containment.
2. Never infer deletion from an unavailable, wrong, partial, or failed scan.
3. Keep catalog effects idempotent across retry and restart.
4. Publish useful progress and failures early.
5. Keep the design small enough to exercise end-to-end behavior quickly.
6. Establish measurement hooks without inventing performance claims.

## 4. Component map

```text
Angular web
  ├── root setup
  ├── scan progress and failures
  └── catalog list and download
          │ relative /api and /actuator URLs
          ▼
Spring Boot yurlib-server
  ├── API
  │   ├── allowed mounts and library roots
  │   ├── scan jobs
  │   ├── catalog query
  │   └── asset download
  ├── Application
  │   ├── configure root
  │   ├── request/recover scan
  │   ├── query catalog
  │   └── stream asset
  ├── Domain
  │   ├── library root and availability
  │   ├── scan job and file outcome
  │   └── work/edition/asset/location/observation
  └── Infrastructure
      ├── contained filesystem access
      ├── EPUB/FB2/MOBI metadata adapters
      ├── bounded in-process scan worker
      └── PostgreSQL repositories
```

The domain and application packages must not depend on Spring MVC, JPA entities, or format-parser implementations. Infrastructure adapters implement filesystem, parser, persistence, and streaming ports.

## 5. Configuration and trust boundary

Deployment configuration maps a stable alias to an allowed canonical mount prefix, for example:

```text
library-main -> /mnt/books
```

The root API accepts `mountAlias`, a normalized relative path, and the expected identity token. It does not accept an unrestricted absolute path. The resolved path must remain within the canonical allowed prefix after normalization and real-path resolution. The server persists only a cryptographic digest of the identity token and never returns or logs the token.

The mount-discovery API returns only stable aliases from deployment configuration. It never returns canonical or physical filesystem paths.

Each root contains an operator-created `.yurlib-root-id` text file whose trimmed content equals the configured identity token. Yurlib reads but never creates or modifies this marker. A missing or mismatched marker makes the root unavailable and prevents missing-location reconciliation.

Symlinks are not followed during discovery. A candidate whose real path escapes the root is rejected and recorded as a security failure. Source mounts are read-only in supported deployment examples.

## 6. Data model

| Record               | Required first-slice fields and invariants                                                                                                                            |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Library Root         | UUID, name, mount alias, relative base path, expected identity digest, mode=`READ_ONLY`, availability, last successful scan                                           |
| Scan Job             | UUID, root ID, state, created/started/heartbeat/completed timestamps, discovered/processed/skipped/failed counters, completion coverage flag, error summary           |
| File Outcome         | job ID, normalized relative path, state, error code, safe diagnostic, attempt count                                                                                   |
| Asset Location       | UUID, root ID, normalized relative path, size, modified time, optional file key, availability, last-seen successful scan; unique on root and normalized relative path |
| Asset                | UUID, format, byte size, original/derived flag (`original` only in this slice), optional future content hash                                                          |
| Work                 | UUID, provisional title and resolution state                                                                                                                          |
| Edition              | UUID, work ID, observed language and identifiers, provisional state                                                                                                   |
| Metadata Observation | UUID, subject ID/type, field, value, source=`FILE`, parser name/version, observed timestamp                                                                           |

An unchanged check compares the location's normalized relative path, size, modified time, and extraction-version identifier. These facts are change hints, not duplicate proof. A later full hash may establish binary identity.

Catalog writes for one candidate occur in one transaction. Reprocessing the same unchanged location must update observations or availability deterministically without creating duplicate Work, Edition, Asset, or Location effects.

## 7. Job states and recovery

```text
QUEUED → RUNNING → SUCCEEDED
                 ↘ COMPLETED_WITH_FAILURES
       ↘ FAILED
       ↘ CANCELLED        (reserved; UI control deferred)
```

- `POST /library-roots/{id}/scans` creates and commits a `QUEUED` job before returning `202`.
- Only one nonterminal scan per root is allowed.
- A bounded in-process worker claims jobs with a database-visible lease/heartbeat.
- On startup, an expired `RUNNING` lease becomes eligible for retry.
- Recovery may traverse from the root again; unchanged candidates are skipped without payload parsing.
- Per-file failures do not fail the whole scan unless root identity, containment, or complete traversal fails.
- Missing locations are reconciled only after identity verification and a complete traversal. Partial or failed scans leave prior availability unchanged.

## 8. Discovery and extraction flow

```text
Validate allowed mount and identity marker
  → walk without following symlinks
  → normalize contained relative path
  → filter plain EPUB/FB2/MOBI candidates
  → read pre-extraction size and modification time
  → skip unchanged candidate or invoke bounded parser adapter
  → read post-extraction size and modification time
  → defer unstable file or persist catalog result/failure transactionally
  → update durable counters
  → reconcile unseen locations only after complete successful coverage
```

Parser adapters must enforce bounded reads and format-specific safety. EPUB ZIP structures require entry-count, expanded-size, ratio, and per-entry limits without general archive ingestion. FB2 XML parsing disables DTDs and external entities. MOBI parsing must use bounded offsets and allocation limits. Parser selection and concrete dependency review belong to the metadata-adapter implementation issue.

The accepted initial dependency and limit review is recorded in [Metadata Adapter Dependency and Safety Review](metadata-adapter-review.md).

## 9. API contract

The initial contract is `contracts/openapi/yurlib-v1.yaml`.

| Operation                                   | Result                                                      |
| ------------------------------------------- | ----------------------------------------------------------- |
| `GET /api/v1/library-mounts`                | List allowed mount aliases without physical paths           |
| `POST /api/v1/library-roots`                | Persist and validate one read-only root; `201`              |
| `GET /api/v1/library-roots`                 | List configured root and availability                       |
| `POST /api/v1/library-roots/{rootId}/scans` | Commit queued job and return job resource; `202`            |
| `GET /api/v1/jobs/{jobId}`                  | Return state, counters, warnings, and safe failures         |
| `GET /api/v1/catalog/works`                 | Page/filter provisional works and available original assets |
| `GET /api/v1/assets/{assetId}/content`      | Revalidate and stream an available contained original asset |

Errors use RFC 9457 Problem Details with a stable `code`, correlation identifier, and safe detail. Initial codes include `ROOT_NOT_ALLOWED`, `ROOT_UNAVAILABLE`, `ROOT_IDENTITY_MISMATCH`, `SCAN_ALREADY_ACTIVE`, `PATH_ESCAPE`, `FILE_UNSTABLE`, `UNSUPPORTED_FORMAT`, `ENCRYPTED_ASSET`, `CORRUPT_ASSET`, `PARSE_LIMIT_EXCEEDED`, and `ASSET_UNAVAILABLE`.

## 10. Angular workflow

1. The client loads allowed aliases from `GET /api/v1/library-mounts`; root setup selects one advertised alias and supplies name, relative path, and identity token.
2. Starting a scan immediately shows the returned job ID and `QUEUED` state.
3. The client polls with bounded backoff while the job is nonterminal and stops polling on component destruction.
4. The progress view shows counts rather than a fabricated percentage when total work is unknown.
5. File failures are summarized with safe relative paths and stable error codes.
6. The catalog is pageable and searchable; provisional/unknown values are visible as such.
7. Download uses the server-provided asset ID and a relative application URL.

The UI must not embed backend hosts. Development and deployment routing provide the `/api` and `/actuator` origins.

## 11. Access model

An explicit loopback-development profile may bypass login only when the server binds to loopback. Any non-loopback listener requires authenticated owner access before M1 release. Administrative root configuration and scan start operations require owner authority; catalog read/download policy remains owner-only for M1.

Authentication implementation is a separate M1 issue so that the walking skeleton can prove ingestion locally without pretending that LAN access is authorization.

## 12. Observability

- Preserve or accept a correlation ID at the API boundary and attach it to scan creation.
- Log root IDs and safe relative paths only at restricted diagnostic levels; do not log physical prefixes or identity tokens.
- Emit job duration, discovered/processed/skipped/failed counts, parser failure counts, and current queue depth.
- Health remains process health; readiness fails when required PostgreSQL connectivity is unavailable, not merely because a library root is disconnected.
- Root availability and scan health are domain/API state, not application liveness.

## 13. Acceptance matrix

| Scenario          | Required evidence                                                                                        |
| ----------------- | -------------------------------------------------------------------------------------------------------- |
| Happy path        | Configure verified root, receive job ID, discover fixtures, see catalog entries, download original bytes |
| Idempotent rescan | Second unchanged scan creates no duplicate catalog effects and reparses zero payloads                    |
| Restart           | Interrupt a running scan, restart application, recover/retry job, preserve idempotent results            |
| Unavailable root  | Missing mount or marker prevents reconciliation and preserves prior catalog/location state               |
| Wrong root        | Identity mismatch fails before discovery and preserves prior state                                       |
| Partial failure   | One malformed book records a failure while valid books complete                                          |
| Unstable file     | Changed pre/post stat is deferred/retryable and not published as a stable asset                          |
| Path safety       | Traversal input and escaping symlink are rejected; source corpus remains byte-for-byte unchanged         |
| XML/ZIP limits    | XXE and decompression-limit fixtures are rejected without external reads or resource exhaustion          |
| Download safety   | Unknown/unavailable asset returns Problem Details; no endpoint accepts a filesystem path                 |
| Contract/UI       | Angular uses relative URLs, stops polling, exposes progress/failures, and downloads by asset ID          |

## 14. Implementation order

1. [#20](https://github.com/yurtools/yurlib/issues/20) — Contract, persistence migration, domain types, and generated fixture corpus.
2. [#23](https://github.com/yurtools/yurlib/issues/23) — Root configuration, mount resolution, marker validation, and path containment.
3. [#15](https://github.com/yurtools/yurlib/issues/15) — Durable job lifecycle, bounded worker, discovery, and restart reclaim.
4. [#16](https://github.com/yurtools/yurlib/issues/16) — EPUB/FB2/MOBI parser adapters and per-file outcomes.
5. [#24](https://github.com/yurtools/yurlib/issues/24) — Transactional catalog reconciliation, search, and unchanged skip behavior.
6. [#25](https://github.com/yurtools/yurlib/issues/25) — Catalog/job/download API completion and contract tests.
7. [#26](https://github.com/yurtools/yurlib/issues/26) — Angular root, progress, failure, catalog, and download workflow against the contract.
8. [#22](https://github.com/yurtools/yurlib/issues/22) — Authentication for non-loopback deployment and full acceptance/security verification.

Parent delivery issue: [#27](https://github.com/yurtools/yurlib/issues/27).

Frontend work may begin against contract fixtures after the OpenAPI schemas stabilize. Filesystem/job behavior must be integrated before expanding UI polish.

## 15. Definition of done

- Every included acceptance scenario has an automated test or documented manual evidence where automation is not practical.
- Testcontainers verifies migrations, durable jobs, idempotent reconciliation, and restart recovery against PostgreSQL.
- Generated or appropriately licensed fixtures cover every supported format and security case; no private books are committed.
- ArchUnit protects domain/application boundaries.
- OpenAPI and implementation remain synchronized.
- Full CI passes, the source fixture corpus is unchanged, and a human can complete the walking-skeleton workflow from documented commands.
