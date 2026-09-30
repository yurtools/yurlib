# M2 Metadata and Bounded Streaming Targets

- Status: Pull request open
- Date: 2026-09-30
- Branch: `fix/47-mobi-metadata`
- Pull request: [#48](https://github.com/yurtools/yurlib/pull/48)
- Design issue: [#41](https://github.com/yurtools/yurlib/issues/41)
- Streaming issue: [#49](https://github.com/yurtools/yurlib/issues/49)
- Repository: `yurtools/yurlib`

## Prompt

> update m2 targets, create an issue with PARSE_LIMIT_EXCEEDED, list it as an issue for all formats, to make sure we are not laoding the full book into memory

## Plan

1. Convert the EPUB false-rejection finding into a cross-format resource-safety target rather than simply increasing one numeric threshold.
2. Add provenance-aware metadata normalization and owner curation to the explicit M2 design outcome.
3. Create an actionable M2 issue covering `PARSE_LIMIT_EXCEEDED` and bounded EPUB, FB2, and MOBI metadata access.
4. Require deterministic evidence that parsers stream or seek bounded structures and never allocate the complete ebook in heap memory.
5. Link the issue to Design M2, the M2 milestone, and the Engineering project.
6. Keep the accepted M1 limits unchanged until the M2 design and implementation are reviewed.

## Architecture outcome

- Raw source observations remain immutable provenance; normalized, resolved, and owner-curated display values are separate states.
- M2 must define deterministic title selection, contributor roles and display names, language normalization, typed identifiers, conflicts, overrides, reprocessing, review, and recovery.
- Metadata extraction receives a format-neutral resource-budget contract covering bytes read, allocations, XML metadata, archive and record structures, selected values, decoded images, open files, elapsed work, and total per-job memory.
- EPUB may inspect bounded ZIP metadata and selected metadata/cover entries without inflating unrelated content.
- FB2 metadata extraction must stream past or skip embedded binary bodies without buffering the complete XML book.
- MOBI extraction must continue bounded record-table, record-zero, offset, and selected EXTH access without whole-source allocation.
- Per-file budgets must compose with bounded multithreaded ingestion so configured concurrency cannot multiply memory use without backpressure.
- `PARSE_LIMIT_EXCEEDED` remains a safe per-file outcome with a diagnostic tied to the specific exceeded resource.

## Executed actions

- Created issue [#49](https://github.com/yurtools/yurlib/issues/49), made it a sub-issue of Design M2 issue #41, assigned it to milestone `M2 — Curation`, labeled it `engineering` and `m2`, added it to the Yurlib Engineering project, and set its status to Todo.
- Updated Design M2 issue #41 with dedicated metadata-quality and bounded-streaming sections plus acceptance criteria that make issue #49 required input.
- Updated the M2 milestone description to include provenance-aware normalization and bounded streaming for every supported format.
- Updated the product milestone summary and recorded issue #49 as the required successor to the accepted M1 metadata-adapter limits.

## Verification

- Issue #49 covers EPUB, FB2, and MOBI separately and prohibits whole-file reads or allocations proportional to total source size.
- Its acceptance criteria require generated or licensed near-limit and over-limit fixtures, byte/read-budget evidence, constrained-memory evidence, safe diagnostics, source-hash preservation, versioned reprocessing, and memory benchmarks.
- No M1 parser limit or runtime behavior changed in this planning step.
- The unrelated local Angular analytics preference remains outside this change.

## Result

M2 now explicitly owns both metadata quality and bounded streaming ingestion. Implementing issue #49 requires design approval through issue #41 before it moves to In Progress.
