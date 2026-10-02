# M2 Merge, Split, and Duplicate-Review Recovery

- Status: In progress
- Started: 2026-10-02
- Branch: `feat/59-merge-split-recovery`
- Issue: [#59](https://github.com/yurtools/yurlib/issues/59)
- Repository: `yurtools/yurlib`

## Prompt

> done

## Plan

1. Reconcile merged pull request #73, close its process log, update `main`, create the issue #59 branch, and preserve the unrelated Angular analytics preference.
2. Review ADR-0005, ADR-0006, M2 design section 5, the canonical catalog schema, personal-state ownership, authorization projections, curation APIs, and existing optimistic/audit conventions.
3. Define explicit preview and execution contracts for Work, Edition, and Contributor merge/split recovery, including stable survivors, affected associations, redirects, before-images, conflicts, and personal-state reconciliation.
4. Persist durable merge history and duplicate decisions without altering observations or source assets; apply mutations transactionally with optimistic versions and idempotent request semantics.
5. Prevent denied identifiers, observations, roots, locations, and provenance from appearing in previews, history, errors, totals, or direct lookups.
6. Add a restrained curator workflow for previews, explicit reasons, confirmation, undo where safe, and guided split explanations where later edits conflict.
7. Add focused domain tests, MVC slices, PostgreSQL integration and failure-injection tests, authorization regressions, OpenAPI coverage, Angular interaction tests, and the full repository verification baseline.

## Frontend direction

- Visual thesis: recovery should feel like a careful editorial proofing desk, with one authoritative survivor and a legible before/after comparison rather than a destructive admin dialog.
- Content plan: candidate context, authorization-safe impact preview, explicit survivor/reason controls, affected-association summary, then history and recovery actions.
- Interaction thesis: previews update before confirmation, irreversible-looking actions require deliberate confirmation, and completion restores focus while showing the surviving canonical identity.

## Actions and results

- Confirmed pull request #73 was squash-merged as `752586b`; issue #58 is closed and its Project item is Done.
- Fast-forwarded local `main`, created `feat/59-merge-split-recovery`, preserved the unrelated Angular analytics preference, and moved issue #59 to Project status In Progress.
- Reviewed accepted ADR-0005 and ADR-0006 plus the approved M2 canonical identity, metadata-state, curation/recovery, and authorization requirements.
- Added Flyway schema version 11 with canonical tombstone links, deferrable read-state reconciliation, durable idempotent merge operations and before/after images, plus rule-version-scoped `NOT_SAME` decisions.
- Added authorization-aware Work, Edition, and Contributor previews and transactional merges. Mutable associations move in place where safe; already-represented associations remain on the tombstoned source so their provenance is not discarded. Immutable observations and source assets are never rewritten by recovery.
- Added stable source redirects, optimistic subject versions, audit events, retry-safe idempotency keys, automatic undo against the recorded after-image, and a guided-split response when later state differs.
- Added a failure-injection seam and PostgreSQL tests proving rollback leaves editions and personal read state on the source and does not create a partial operation.
- Added capability-protected recovery endpoints for preview, merge, split preview, undo, history, and not-the-same decisions. Restricted users receive `SUBJECT_NOT_FOUND` when any evidence for a requested subject belongs to a denied root.
- Excluded merged Work and Contributor tombstones from catalog search and ordinary curation lookups while retaining redirect/history access through the survivor.
- Extended the OpenAPI contract and added a restrained duplicate-proofing desk to the existing curation workspace. The UI keeps the selected Work as survivor, requires an impact preview and reason, displays association counts and conflicts, records not-the-same decisions, and checks automatic-undo safety before recovery.
- Corrected PMD findings in recovery query helpers and removed an unnecessary SpotBugs suppression; the final static-analysis run is clean.
- Preserved the unrelated user-owned `web/yurlib-web/angular.json` analytics preference without staging or modifying it.

## Verification

- Flyway migration through schema version 11 — passed against PostgreSQL 18.
- `./mvnw -pl services/yurlib-server -Dtest=CatalogRecoveryIntegrationTest test` — passed after correcting canonical UUID ordering to match PostgreSQL; covers Work merge/undo, observation retention, personal-state reconciliation, idempotent retry, later-edit conflict, rule-version-scoped duplicate decisions, authorization non-disclosure, Edition/Contributor recovery, and injected transactional rollback.
- The test phase of `./mvnw verify` — passed; 159 tests, 0 failures, 0 errors, and 1 skipped. The same run subsequently reported static-analysis findings, which were corrected.
- `./mvnw spotless:apply` — completed and left the Java sources consistently formatted.
- `./mvnw verify -DskipTests` — passed after the static-analysis corrections; Checkstyle, PMD, and SpotBugs are clean.
- `./mvnw -pl services/yurlib-server -Dtest=CatalogRecoveryIntegrationTest,CatalogRecoveryControllerTest,OpenApiContractTest test` — passed against the final code; 8 tests, 0 failures, 0 errors, and 0 skipped.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages installed, 0 vulnerabilities. npm reported the repository's existing blocked optional install scripts.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed; 16 tests, including duplicate-impact preview interaction.
- `npm --prefix web/yurlib-web run build` — passed; production bundle generated.
- `docker compose config` — passed.
- `git diff --check` — passed.

## Remaining acceptance

- Manual desktop and mobile visual acceptance of the recovery workspace remains for pull-request review.

## Blockers

- None.
