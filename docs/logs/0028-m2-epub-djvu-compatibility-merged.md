# M2 EPUB and DjVu Compatibility

- Status: Merged
- Started: 2026-10-01
- Branch: `feat/69-epub-djvu-compatibility`
- Issue: [#69](https://github.com/yurtools/yurlib/issues/69)
- Pull request: [#71](https://github.com/yurtools/yurlib/pull/71)
- Repository: `yurtools/yurlib`

## Prompt

> merged, go ahead

## Plan

1. Reconcile merged pull request #68, close its process log, and start issue #69 from merged `main`.
2. Accept ordinary EPUB ZIP directory entries while retaining strict absolute-path, separator, empty-segment, dot-segment, and traversal rejection.
3. Support bounded nested DjVu `FORM:DJVI` components without decoding their payloads or counting them as pages.
4. Compare DjVu padding and top-level length outcomes with DjVuLibre, then encode valid and invalid boundary behavior in generated regression fixtures.
5. Bump affected parser and aggregate extraction versions, verify idempotent reprocessing and preservation of curated values, and rerun the aggregate corpus benchmark without recording private names or content.
6. Update architecture evidence, issue acceptance, and the process log; run the full verification baseline before opening a pull request.

## Actions and results

- Confirmed pull request #68 was squash-merged as `604c15f`; issue #56 is closed and its Project item is Done.
- Fast-forwarded local `main`, created `feat/69-epub-djvu-compatibility`, preserved the unrelated local Angular analytics preference, and moved issue #69 to Project status In Progress.
- Compared all 87 corpus DjVu files with the locally installed DjVuLibre `djvudump` reference parser using aggregate-only output: 86 were accepted and one was rejected.
- Confirmed the 86 reference-accepted files have exact top-level lengths. The one reference-rejected file is truncated, so Yurlib must retain its top-level length rejection.
- Correlated the earlier aggregate Yurlib outcomes: the 86 reference-accepted files are the 67 valid nested `FORM:DJVI` cases plus 19 valid terminal-padding-boundary cases. No filenames, metadata values, or content were recorded.
- The first corpus rerun exposed that reading every one-byte interior pad with a separate positional SMB read caused excessive network round trips. Stopped that run and retained the same boundary validation without reading unused pad bytes; the declared container bounds already prove that each skipped interior byte exists.
- A second run showed that accepting multipage DjVu exposed unnecessary traversal of every page and shared dictionary. Refined the bounded behavior to skip `DJVI` payloads, inspect only the first `DJVU` page for selected metadata, count all page forms, and reconcile that count with `DIRM`. This also prevents double-counting pages when a directory precedes embedded page forms.
- The next run exposed an existing positional-reader inefficiency: every small chunk read re-queried source size, producing millions of SMB metadata calls once valid multipage files progressed beyond the former early rejection. Cached the size for the lifetime of the already-open read handle while retaining pre/post file-fact instability checks and identical range bounds.
- Updated EPUB validation to accept explicit directory entries while rejecting absolute names, backslashes, empty and dot segments, traversal, and canonical file/directory collisions.
- Added generated fixtures for valid EPUB directory entries, unsafe and colliding EPUB names, nested DjVu shared forms, valid terminal odd-length boundaries, missing interior padding, and truncated top-level forms.
- Compared the generated DjVu fixtures with DjVuLibre: the shared-form fixture is accepted, while the missing-interior-padding and truncated fixtures are rejected.
- Bumped EPUB provenance to `jdk-epub` version `3`, DjVu provenance to `jdk-djvu-iff` version `2`, and aggregate extraction provenance to `bounded-metadata-v5`. Updated integration coverage to verify that active curated values survive reprocessing from version 4 to version 5.
- Completed the four-core, 512-MiB-heap SMB benchmark. It extracted 135 of 136 files: all 46 EPUB, two DOCX, one MOBI, and 86 DjVu files. The sole failure is the reference-rejected truncated DjVu and remains `CORRUPT_ASSET`.
- Recorded 18.32 warm files/second, 4,051,056 counted bytes, 218,213,520 peak heap bytes, two peak open files per task, 442.343 microseconds database p95, and 4,290.697 microseconds interactive API p95. The approximately ten-minute wall duration includes 100 additional full-corpus interaction-load passes and is not the warm-pass interval.
- Completed issue #69's acceptance checklist and recorded the aggregate-only verification results in its GitHub discussion.
- Committed the implementation as `c358329`, pushed the feature branch, and opened pull request #71 with `Closes #69`.
- Pull request #71 was squash-merged as `eab7f79`; issue #69 is closed and its Project item is Done.

## Verification

- `./mvnw -B -pl services/yurlib-server -Dtest=BoundedMetadataExtractorTest test` passed 28 tests after final formatting.
- DjVuLibre accepted the generated shared-form fixture and rejected the generated missing-interior-padding and truncated fixtures.
- Aggregate-only corpus comparison: DjVuLibre accepted 86 DjVu files and rejected one; Yurlib now reports the same 86 extracted and one safe failure.
- `./mvnw -B verify` passed 146 tests across the server and document worker, with one environment-gated benchmark skipped; coverage, PMD, SpotBugs, formatting, and API compatibility passed.
- `npm --prefix web/yurlib-web ci` completed with zero reported vulnerabilities. npm retained its known warnings for four blocked optional install scripts.
- `npm --prefix web/yurlib-web test -- --watch=false` passed 13 tests in two files.
- `npm --prefix web/yurlib-web run build` completed successfully.
- `docker compose config --quiet` completed successfully.

## Blockers

- None.
