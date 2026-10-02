# M2 Personal Library State

- Status: In progress
- Started: 2026-10-02
- Branch: `feat/58-personal-library-state`
- Issue: [#58](https://github.com/yurtools/yurlib/issues/58)
- Repository: `yurtools/yurlib`

## Prompt

> merged, continue

## Plan

1. Reconcile merged pull request #72, close its process log, start issue #58 from merged `main`, and preserve the unrelated Angular analytics preference.
2. Review ADR-0006, M2 design sections 5, 6, 11, 12, 16, and 17, and the existing persisted-user, authorization-projection, catalog, OpenAPI, and Angular seams.
3. Add relational, per-user state for idempotent favorite contributors, versioned Work read state with optional Edition evidence, and private collections with explicit optional ordering.
4. Enforce current-user ownership and Work/contributor visibility in PostgreSQL predicates; retain hidden Work membership while omitting it from visible collection contents and counts.
5. Add accessible APIs and restrained catalog UI for favorites, read state, and collection CRUD with optimistic concurrency and stable Problem Details.
6. Add export/account-removal/recovery evidence and stable schema seams for the later merge/split issue without implementing merge/split ahead of issue #59.
7. Add unit, web-slice, PostgreSQL integration, cross-user, OpenAPI, and Angular tests; run the full repository baseline and open a pull request.

## Frontend direction

- Visual thesis: personal state should read as quiet marginalia on the existing editorial shelf, not as another dashboard.
- Content plan: compact favorite/read actions beside each visible Work, followed by a private collections workspace with direct names, counts, and membership controls.
- Interaction thesis: immediate state confirmation, restrained disclosure for collection editing, and focus-preserving updates with reduced-motion behavior.

## Actions and results

- Confirmed pull request #72 was squash-merged as `90147f0`; issue #57 is closed and its Project item is Done.
- Fast-forwarded local `main`, created `feat/58-personal-library-state`, preserved the unrelated Angular analytics preference, and moved issue #58 to Project status In Progress.
- Reviewed ADR-0006 and the approved M2 personal-state, authorization, API, schema, acceptance, and implementation-order requirements.
- Added Flyway schema version 10 with user-owned favorite contributors, versioned Work read state with optional Edition evidence, private collections, optional stable positions, removed-account state, and a reusable authorization-aware Work projection.
- Added current-user application/API operations for state snapshots and export, idempotent favorites, read/unread changes, collection CRUD, and collection membership. Mutations use optimistic versions and RFC 9457 responses.
- Added canonical contributor identifiers to catalog responses so reader controls persist favorites against Contributor IDs rather than display text.
- Added PostgreSQL enforcement that completed Editions belong to the selected Work and that personal-state references cannot be silently orphaned before issue #59 performs merge/split reconciliation.
- Added access-filtered projections: denied Works and contributors disappear from favorites, read state, collection contents, and visible counts while the underlying user-owned state remains intact and returns when access is restored.
- Added non-owner account removal that deletes private state, capabilities, and root denies; invalidates authority; anonymizes the retained account identity for audit foreign keys; and prevents removed identities from authenticating or appearing in administration results.
- Added the restrained “Your library” UI, including keyboard-accessible read/favorite controls, collection creation and editing, optional ordered membership, visible-content counts, and per-Work add/remove actions.
- Extended the reviewed OpenAPI contract with personal-state/export resources and account removal. Added controller, PostgreSQL integration, OpenAPI, and Angular interaction coverage.
- Preserved the accepted implementation boundary: issue #59 owns merge/split operations and previews, while this change supplies private-state ownership and restrictive references; issue #61 owns the destructive backup/restore drill, while these relational tables are included in PostgreSQL backup state.
- Fixed PostgreSQL timestamp writes to use explicit JDBC timestamps after the first integration test exposed ambiguous `Instant` binding.
- Added complete invalid-request Problem Details handling for the new API and documented the Spring-owned application-port lifetime for SpotBugs after the first static-analysis run reported `EI_EXPOSE_REP2`.

## Verification

- `./mvnw -pl services/yurlib-server -Dtest=PersonalLibraryIntegrationTest test` — passed; two PostgreSQL tests cover privacy, access removal/restoration, version conflicts, Edition validation, ordered membership, and account removal.
- `./mvnw -pl services/yurlib-server -Dtest=OpenApiContractTest,PersonalLibraryControllerTest test` — passed; four tests.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed; 15 tests.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages installed and 0 vulnerabilities. npm reported the existing blocked optional install-script warning for four packages.
- `npm --prefix web/yurlib-web run build` — passed; production bundle generated.
- `docker compose config` — passed.
- `git diff --check` — passed.
- Full Maven verification completed all 150 server tests with one benchmark skipped; its first static-analysis pass then reported one SpotBugs dependency-lifetime warning in the new controller. The constructor now carries the same scoped suppression and justification used by existing Spring controllers.
- `./mvnw -pl services/yurlib-server -Dtest=PersonalLibraryControllerTest,PersonalLibraryIntegrationTest test` — passed after the final controller change; five tests.
- `./mvnw verify -DskipTests` — passed after the final controller change; formatting, additive API compatibility, compilation, packaging, coverage checks from the completed test run, PMD, and SpotBugs are clean for both Java modules.

## Blockers

- None.
