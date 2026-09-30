# Cyrillic Book Support Verification

- Status: In progress
- Started: 2026-09-29
- Branch: `test/43-cyrillic-book-support`
- Issue: [#43](https://github.com/yurtools/yurlib/issues/43)
- Repository: `yurtools/yurlib`

## Prompt

> test cyrrilic books support

## Plan

1. Record focused acceptance criteria and track the work in the Yurlib Engineering project.
2. Add a synthetic UTF-8 FB2 fixture with a Cyrillic filename, title, contributor, body text, and Russian language code.
3. Prove bounded metadata extraction preserves the Cyrillic values and source bytes.
4. Prove PostgreSQL persistence and catalog queries return Cyrillic metadata unchanged.
5. Prove case-insensitive Cyrillic title/contributor and filename search through the baseline PostgreSQL implementation.
6. Prove the Angular workbench renders Cyrillic metadata and sends Cyrillic queries unchanged.
7. Run focused tests, the full backend verification gate, and the frontend test/build gates.
8. Record limitations, commit and push the branch, and open a pull request linked to issue #43.

## Execution log

### 2026-09-29 — Preflight and implementation

- Created issue #43 and added it to the Yurlib Engineering project as In Progress.
- Created branch `test/43-cyrillic-book-support` from release tag commit `v0.1.0-m1` on synchronized `main`.
- Confirmed the existing Japanese fixture proved only a Unicode filename and `ja` language extraction; it did not prove non-Latin metadata or search.
- Added an original synthetic Cyrillic FB2 fixture so no private or copyrighted book content enters the repository.
- Added parser, PostgreSQL integration, and walking-skeleton assertions for Cyrillic metadata and search.
- Added an Angular component test for Cyrillic catalog rendering and unchanged query transport.

## Verification

- `./mvnw -B -pl services/yurlib-server -am spotless:apply test -Dtest=BoundedMetadataExtractorTest,FixtureCorpusTest,YurlibServerIntegrationTest,M1WalkingSkeletonAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false` — passed, 26 focused tests.
- `./mvnw -B verify` — passed, 90 tests; formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs gates passed.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 10 tests.
- `npm --prefix web/yurlib-web run build` — passed, production bundle generated successfully.
- `git diff --check` — passed.
- `npm ci` was not repeated because no frontend dependency or lockfile changed. Compose validation was not repeated because no deployment configuration changed.

## Results

- The bounded FB2 parser preserves a UTF-8 Cyrillic filename, title, contributor, body, and `ru` language metadata without changing fixture source bytes.
- PostgreSQL 18 preserves the title, contributor, language, and filename, and the catalog returns them unchanged.
- Catalog search matches Cyrillic title, contributor, and filename fragments across tested upper/lowercase forms.
- The Angular workbench renders Cyrillic title/contributor text and sends Cyrillic search input unchanged.
- Transliteration, accent folding, language filtering, localized UI strings, and broader locale-specific collation behavior remain outside this verification scope.
- Commit, push, pull request, and merge results are pending.
