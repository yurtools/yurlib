# M2 Bounded Metadata Streaming

- Status: In progress
- Started: 2026-09-30
- Branch: `feat/49-bounded-metadata-streaming`
- Issue: [#49](https://github.com/yurtools/yurlib/issues/49)
- Repository: `yurtools/yurlib`

## Prompt

> merged, proceed

## Plan

1. Reconcile merged authorization pull request #64 and start issue #49 from merged `main`.
2. Document one format-neutral metadata resource-budget contract with classified limits and safe diagnostics.
3. Refactor EPUB, FB2, and MOBI extraction to bounded seek/stream access without whole-file allocation.
4. Add generated near-limit, over-limit, large-payload, read-budget, allocation, and constrained-heap evidence while preserving source hashes.
5. Make parser/extraction version changes reprocess affected Assets idempotently and record bytes-read/peak-memory reference evidence.
6. Run the full verification baseline, update issue acceptance, and open a short-lived pull request.

## Actions and results

- Confirmed pull request #64 merged as `aa0446375546aa4dc4a44eb15a881ec9fd0b1831`; issue #54 is closed and its Project item is Done.
- Moved issue #49 to Project status In Progress.
- Created branch `feat/49-bounded-metadata-streaming` from merged `origin/main` while preserving the unrelated local Angular analytics preference.
- Replaced the coarse M1 rules with one extraction-scoped budget for admitted source size, aggregate bytes read, selected XML, archive structures, selected scalar values, nesting depth, open handles, and elapsed time.
- Refactored EPUB to inspect a bounded central directory and inflate only `mimetype`, the container document, and the selected OPF. Large unrelated content is accepted while traversal, encryption, expansion, compression-ratio, and XML safety checks remain operation-specific.
- Replaced FB2 DOM/whole-document parsing with secure StAX selection that returns at `</description>`, rejects external XML features, and applies exact 4 MiB metadata-prefix, 64 KiB scalar, depth, read, and time bounds.
- Replaced MOBI record-zero buffering with bounded positional reads of the PDB directory, required MOBI fields, EXTH headers and selected values, and the optional full-name range.
- Added generated near/over-boundary fixtures, large binary/image/sparse-source fixtures, deterministic bytes-read and controlled-buffer assertions, safe diagnostic assertions, and success/failure source-preservation checks.
- Bumped parser provenance to `jdk-epub` 2, `jdk-fb2-stax` 2, and `jdk-mobi-seek` 3 and the aggregate extraction version to `bounded-metadata-v3`; integration coverage proves older observations reprocess once while curated values remain separate.
- Added an architecture rule that rejects production metadata calls to `Files.readAllBytes`, `Files.readString`, and `InputStream.readAllBytes`.
- Recorded final reference-corpus evidence under a 512 MiB heap and four active processors. The 96 MiB FB2 averaged 8,230 counted bytes and the sparse 256 MiB MOBI averaged 364 counted bytes.

## Verification

- `./mvnw -B verify` — passed; 115 tests, formatting, OpenAPI compatibility, coverage, PMD, SpotBugs, and PostgreSQL Testcontainers integration are green.
- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -XX:MaxRAM=8g -Xmx512m' taskset -c 0-3 ./mvnw -B -pl services/yurlib-server -Djacoco.skip=true -Dtest=MetadataReferenceBenchmarkTest test` — passed; final bytes-read, controlled-buffer, peak-heap, and throughput observations are in `docs/architecture/m2-metadata-resource-evidence.md`.
- `npm --prefix web/yurlib-web ci` — passed; 268 packages audited with no vulnerabilities. npm reported four blocked dependency install scripts; no approval was required for the successful build.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed; 11 tests in 2 files.
- `npm --prefix web/yurlib-web run build` — passed; production bundle generated.
- `docker compose config` — passed.
- `git diff --check` — passed.

## Blockers

- None.
