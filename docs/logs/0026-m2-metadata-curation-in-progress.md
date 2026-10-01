# M2 Metadata Normalization and Curation

- Status: Ready for pull request
- Started: 2026-10-01
- Branch: `feat/55-metadata-curation`
- Issue: [#55](https://github.com/yurtools/yurlib/issues/55)
- Repository: `yurtools/yurlib`

## Prompt

> merged, continue

## Plan

1. Reconcile merged pull request #66 and start issue #55 from merged `main`.
2. Implement deterministic, versioned normalization and resolution for titles, contributor names and roles, BCP 47 languages, and typed identifiers.
3. Add bounded review queues for conflicts and invalid or ambiguous values.
4. Add curator-only overrides with actor, reason, history, undo, and optimistic concurrency.
5. Add shared catalog tags and authorization-filtered search.
6. Add accessible catalog correction, conflict review, tag, and audit UI.
7. Verify backend, frontend, API compatibility, migrations, and Compose configuration; then open a pull request that closes #55.

## Actions and results

- Confirmed pull request #66 merged as `93445850ae4afba5e65890de7f2c1dff20b3721d`; issue #51 is closed and its Project item is Done.
- Created branch `feat/55-metadata-curation` from merged `origin/main` while preserving the unrelated local Angular analytics preference.
- Moved issue #55 to Project status In Progress.
- Reviewed the accepted M2 curation design, ADR-0005, ADR-0006, the canonical catalog schema, persisted authorization, API, and Angular boundaries.
- Added schema version 7 with a bounded metadata-review queue, shared catalog tags, and curated contributor-alias provenance.
- Added deterministic normalization for Unicode titles and contributor names, contributor roles, BCP 47 language tags, and typed ISBN, DOI, UUID, URI, local-source, and other identifiers.
- Integrated normalization and resolution with catalog observation persistence while retaining raw observations and preserving active curated values during reprocessing.
- Kept contributor identity resolution within the same Work so matching aliases cannot silently merge contributors across unrelated works.
- Added curator operations for title correction and undo, contributor display-name and alias correction, shared tags, review dismissal, and audit history. Mutations require an actor, reason, and current version for optimistic concurrency.
- Added authorization filtering for curation details and review results and gated shared changes with `CURATE_CATALOG` in owner mode.
- Extended the OpenAPI contract and Angular client with accessible inline correction, contributor, tag, review, and history controls.
- Added unit, controller, PostgreSQL integration, migration, contract, authorization, and Angular component/API coverage.
- Applied narrow documented SpotBugs suppressions only where constructor-injected collaborators are deliberately retained and Unicode normalization is intentionally locale-neutral.
- Renamed log 0025 from `pr-open` to `merged` after pull request #66 was squash-merged.

## Verification

- `./mvnw verify` — passed; the server ran 130 tests and the isolated document worker ran 3 tests. Coverage gates, PMD, SpotBugs, packaging, and all three Maven reactor modules passed.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages installed, zero reported vulnerabilities. npm reported the existing blocked-install-script notices for four optional/native packages.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed; 13 tests.
- `npm --prefix web/yurlib-web run build` — passed; production bundle generated.
- `docker compose config --quiet` — passed.
- `git diff --check` — passed.
- The local `web/yurlib-web/angular.json` analytics preference is unrelated and will not be staged or committed.

## Blockers

- None.
