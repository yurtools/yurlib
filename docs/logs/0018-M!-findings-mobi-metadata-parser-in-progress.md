# M! Findings — MOBI Metadata Parser

- Status: In progress
- Started: 2026-09-30
- Branch: `fix/47-mobi-metadata`
- Issue: [#47](https://github.com/yurtools/yurlib/issues/47)
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
- Issue #47 contains the acceptance criteria and is tracked as In Progress in the Yurlib Engineering project.
