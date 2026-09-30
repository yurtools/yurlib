# M1 Findings — MOBI Metadata Parser

- Status: Merged
- Started: 2026-09-30
- Branch: `fix/47-mobi-metadata`
- Issue: [#47](https://github.com/yurtools/yurlib/issues/47)
- Pull request: [#48](https://github.com/yurtools/yurlib/pull/48)
- Reconciliation pull request: [#50](https://github.com/yurtools/yurlib/pull/50)
- Implementation commit: `51711a8`
- Squash-merge commit: `866e059`
- Repository: `yurtools/yurlib`

## Prompt

> execute the fix. in the logs, create a file with "...-M!-findings-..." to describe the issue

## Plan

1. Record the production-shaped metadata symptom without copying private ebook content into the repository.
2. Correct the bounded MOBI full-name field offsets and retain explicit EXTH updated-title precedence.
3. Replace the fallback-only generated MOBI fixture with realistic UTF-8 full-name and EXTH metadata structures.
4. Verify Cyrillic title, contributor, language, identifier, updated-title, limit, encryption, and source-preservation behavior.
5. Bump the extraction and MOBI parser versions so unchanged cataloged assets are reparsed on the next scan.
6. Run focused tests, the full repository verification baseline, and a disposable live rescan where practical.
7. Commit, push, and open a pull request linked to issue #47.

## Findings

- A completed disposable-library scan exposed incorrect ASCII-like titles for MOBI files whose valid UTF-8 titles were present in the files.
- The MOBI full-name offset and length fields are at header-relative offsets `0x44` and `0x48`. The parser used decimal offsets 84 and 88 (`0x54` and `0x58`), reading 16 bytes too far forward.
- The first realistic fixture run exposed the same duplicated-header-offset error in the EXTH-present flag: the field is at header-relative `0x70`, while the parser read `0x80`. That made valid contributors, identifiers, language, and EXTH updated titles appear absent.
- When those incorrect fields yielded no usable value, extraction fell back to the Palm database name. Common MOBI writers make that compatibility name ASCII-safe, so it is not authoritative display metadata.
- The original generated fixture left the full-name offset and length fields zero and asserted only the Palm-name fallback. It therefore could not detect the defect.
- The catalog skip decision includes the global extraction version. Incrementing it is required; otherwise unchanged files retain metadata produced by the defective parser.
- Calibre's MOBI writer identifies `0x44` and `0x48` as the title offset and length fields: <https://github.com/kovidgoyal/calibre/blob/master/src/calibre/ebooks/mobi/writer2/main.py#L1598-L1604>.

## Implementation

- Added named MOBI header constants and corrected full-name offset/length reads.
- Corrected the EXTH-present flag offset after the realistic fixture made the hidden second error observable.
- Bumped the MOBI parser provenance version to `2` and the bounded extraction version to `bounded-metadata-v2`.
- Expanded generated MOBI fixtures with bounded UTF-8 full-name, contributor, language, ISBN, ASIN, and optional EXTH updated-title records.
- Extended parser and walking-skeleton acceptance assertions so a Palm-name fallback cannot masquerade as successful title extraction again.
- Preserved the unrelated local Angular analytics preference without staging or modifying it.

## Verification

- `./mvnw -B -pl services/yurlib-server -am spotless:apply test -Dtest=BoundedMetadataExtractorTest,FixtureCorpusTest,M1WalkingSkeletonAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Passed: 14 tests, 0 failures, 0 errors.
- `./mvnw -B verify`
  - Passed: 91 tests, 0 failures, 0 errors.
  - Spotless, JaCoCo coverage checks, PMD, and SpotBugs passed.
- `docker compose config --quiet`
  - Passed.
- `git diff --check`
  - Passed.
- Frontend tests and build were not repeated because this fix does not change frontend source or configuration. The unrelated local `angular.json` preference remains outside this change.

## Results

- Valid MOBI full-name fields are now read from the correct header-relative offsets.
- EXTH metadata is detected using the correct flag field, restoring contributor, language, identifier, and updated-title extraction.
- Cyrillic MOBI titles and contributors are covered by generated-fixture and walking-skeleton tests.
- Existing assets recorded with `bounded-metadata-v1` will be parsed again by the next scan under `bounded-metadata-v2` without changing source files.
- Issue #47 is closed and its Yurlib Engineering project item is Done.
- Pull request #48 passed all required checks and was squash-merged into protected `main` as `866e059`.

## EPUB verification follow-up

The owner requested verification that EPUB files were imported and that their metadata and scan errors were correct. The running local stack was inspected read-only without recording private titles, contributors, filenames, or physical paths.

- The latest scans discovered three EPUB candidates. Two were imported and one was recorded as a safe per-file failure.
- For both imported EPUBs, the persisted title and language exactly matched the normalized package metadata. Persisted contributor and identifier counts also exactly matched their package metadata.
- Focused generated-fixture verification passed 11 tests across `BoundedMetadataExtractorTest` and `M1WalkingSkeletonAcceptanceTest`. This covers EPUB extraction, catalog publication, original-byte download, unchanged rescan, unsafe ZIP paths, decompression limits, and encrypted EPUB classification.
- The rejected EPUB has five embedded `.bmp` entries over the current 1 MiB per-entry limit; its largest entry is 16,178,278 bytes. The complete source is 8,132,599 bytes, total declared expansion is 28,317,396 bytes, and its maximum compression ratio is 6.3:1.
- The rejected archive remains within the separately accepted 256 MiB source, 64 MiB total expansion, and 100:1 ratio limits. Its `PARSE_LIMIT_EXCEEDED` result is therefore caused only by applying the 1 MiB XML-oriented entry limit to unparsed image resources.
- No EPUB parser change was made in this pull request. Relaxing or separating the accepted per-entry bound changes a documented security boundary and requires an explicit follow-up decision and regression fixture.

### EPUB metadata-quality clarification

The equality check above establishes extraction fidelity only; it does not establish bibliographic correctness. A subsequent semantic review confirmed the owner's observation that the displayed metadata still looks incorrect:

- The source EPUB packages contain OCR/importer metadata with inconsistent title capitalization and punctuation, mixed two- and three-letter language codes, and creator names stored in display-unfriendly source order.
- One imported EPUB contains no `dc:creator`, so the missing contributor is faithful to the package but incomplete as catalog metadata.
- Archive access text, opaque source identifiers, ARKs, and ISBNs are all currently retained under positional keys such as `identifier-1`; the EPUB adapter does not classify or normalize identifier types.
- The adapter selects the first title and language and preserves creator text verbatim. M1 intentionally exposes these as provisional observations and does not infer replacements from filenames or descriptions.
- Therefore the parser is faithfully preserving the available package fields, while metadata normalization, provenance-aware resolution, and owner curation remain required to produce reliable display metadata. Those concerns belong in the M2 curation design rather than being silently rewritten during ingestion.

After one additional source file was added and scanned, the live catalog contained three imported EPUB assets and one safely rejected EPUB. The new import followed the same source-faithful behavior described above.
