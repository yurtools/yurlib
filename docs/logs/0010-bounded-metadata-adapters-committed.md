# Bounded EPUB, FB2, and MOBI Metadata Adapters

- Status: Committed
- Started: 2026-09-29
- Branch: `feature/16-bounded-metadata-adapters`
- Issue: [#16](https://github.com/yurtools/yurlib/issues/16)
- Repository: `yurtools/yurlib`

## Prompt

> go ahead

## Plan

1. Confirm issue #15 completion and synchronize the new branch from protected `main`.
2. Review the accepted extraction architecture, generated fixtures, parser dependency options, and licenses before adding code or dependencies.
3. Define a framework-independent application port and immutable provenance-bearing extraction result with explicit safe outcomes.
4. Implement bounded plain EPUB, FB2, and MOBI adapters using limits appropriate to each format.
5. Detect pre/post file changes and return a deferred unstable-file outcome without publishing parsed metadata.
6. Prove valid metadata, absent values, corrupt/encrypted/unsupported/unstable inputs, XXE rejection, archive traversal, and decompression/allocation limits with focused unit tests.
7. Run the full repository verification baseline, commit, push, and open a pull request linked to issue #16.

## Execution log

### 2026-09-29 — Preflight

- Confirmed pull request #33 was squash-merged as `b4277c8`; issue #15 is closed and its Project item is `Done`.
- Created `feature/16-bounded-metadata-adapters` from synchronized `origin/main`.
- Read the project Java/Spring and Spring Boot testing skill instructions and selected plain JUnit tests for parser logic.
- Confirmed issue #16 is the next accepted implementation task and currently `Todo`.
- Inspected the generated fixture corpus: valid and adversarial FB2 sources plus generated EPUB, decompression, traversal, MOBI, and symlink cases.

### 2026-09-29 — Dependency and license review

- Selected only Java runtime APIs (`java.util.zip`, secure JAXP XML parsing, NIO channels, and charset support) for the initial adapters.
- No third-party parser dependency is required, so no additional license enters the GPL-3.0-or-later project dependency set.
- This deliberately small parser surface reads only the metadata structures required by M1 and keeps adapters replaceable behind an application port.

### 2026-09-29 — Implementation

- Added the framework-independent `MetadataExtractor` port, immutable provenance-bearing metadata, and explicit extracted/deferred/failed results.
- Added safe codes for unstable, unsupported, encrypted, corrupt, and limit-exceeded candidates.
- Implemented the EPUB adapter with bounded central-directory inspection, encryption detection, entry-count/per-entry/total-expansion/compression-ratio limits, entry-path validation, mimetype/container checks, and bounded secure OPF parsing.
- Implemented the FB2 adapter with a bounded source read and secure JAXP configuration that rejects DTD declarations, external entities, external schema access, XInclude, and entity expansion.
- Implemented the MOBI adapter with bounded NIO reads, record-count and monotonic-offset validation, supported encoding/compression checks, encryption detection, and bounded selected EXTH metadata.
- Added pre/post NIO file facts. Changed files return `FILE_UNSTABLE` as deferred and do not expose the parsed metadata.
- Registered the default extractor as a Spring bean while keeping the application port and result types independent of Spring.
- Added `docs/architecture/metadata-adapter-review.md` and linked it from the vertical-slice architecture.
- Extended the generated EPUB fixture with an observed contributor; source fixtures remain byte-for-byte protected during tests.

### 2026-09-29 — Test findings and corrections

- The first focused test exposed that a leading `..` segment can survive lexical normalization; EPUB validation now rejects `.` and `..` segments explicitly.
- Configured a throwing XML error handler so malformed and prohibited XML becomes a classified safe result without parser diagnostics on stderr.
- The first full gate required formatting of the new architecture document; Spotless applied the deterministic Markdown format.
- PMD identified lost exception causes and literal-order comparisons; exception causes are now retained and comparisons follow the repository rules.
- SpotBugs identified a possible root-path filename dereference and a redundant MOBI length condition; both were corrected.
- Added eight focused plain-JUnit scenarios covering valid EPUB/FB2/MOBI metadata and provenance, honest absent values, malformed/XXE FB2, traversal/decompression EPUB, encrypted EPUB/MOBI, excluded `.fb2.zip`, MOBI record limits, unstable files, and fixture-source preservation.

## Verification

- `./mvnw -B -pl services/yurlib-server -am spotless:apply test -Dtest=BoundedMetadataExtractorTest -Dsurefire.failIfNoSpecifiedTests=false` — passed, 8 focused tests.
- `./mvnw -B verify` — passed, 50 tests; formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs gates passed.
- `npm --prefix web/yurlib-web ci` — passed; 0 vulnerabilities reported. npm reported the existing blocked-install-script notices.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 3 tests.
- `npm --prefix web/yurlib-web run build` — passed.
- `docker compose config` — passed.

## Result

Implementation and local verification are complete in commit `2e6bcd8` (`feat: add bounded metadata adapters (#16)`). Push and pull request creation are pending.
