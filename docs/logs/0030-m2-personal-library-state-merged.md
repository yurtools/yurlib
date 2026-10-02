# M2 Personal Library State

- Status: Merged
- Started: 2026-10-02
- Branch: `feat/58-personal-library-state`
- Issue: [#58](https://github.com/yurtools/yurlib/issues/58)
- Pull request: [#73](https://github.com/yurtools/yurlib/pull/73)
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
- Committed the implementation as `1884237`, pushed the feature branch, and opened pull request #73 with `Closes #58` and an explicit manual desktop/mobile acceptance checklist.
- During manual acceptance, the documented owner-recovery command exposed two issue-#58 defects: its non-web context still created the internal servlet security chain, and its default recovery-mode property was overridden by `application.yml`. Scoped the worker chain to servlet web applications, forced recovery arguments at command-line precedence, and added focused regression tests. The repaired command completed without exposing the temporary credential.
- The sign-out/sign-in persistence check exposed a stale-CSRF defect: sign-out rendered the login form without fetching the replacement anonymous session and CSRF cookie, so an immediate login was denied until reload. The Angular shell now reloads anonymous session state after logout, with regression coverage; immediate keyboard sign-in then passed without a reload.
- Pull request #73 was squash-merged as `752586b`; issue #58 is closed and its Project item is Done.

## Manual visual acceptance

- Environment: retained PostgreSQL catalog and volume, backend on `127.0.0.1:18080`, PostgreSQL on `127.0.0.1:55432`, and Angular on `http://localhost:4200/` through a temporary `.local/proxy.visual.json`. No scan was started and no catalog/source data was removed.
- Desktop `1440×900`: passed. The initial access/loading shell and authenticated header rendered with owner identity; the full workspace had no clipping, overlap, off-screen controls, or horizontal overflow. `document.documentElement.scrollWidth === document.documentElement.clientWidth` returned `true`.
- Mobile `390×844`: passed. The responsive header, workspace, catalog actions, collection editor, and footer remained readable and operable without clipping, overlap, or horizontal overflow. `document.documentElement.scrollWidth === document.documentElement.clientWidth` returned `true`.
- Favorite/read state: at both viewports, favorited and unfavorited a canonical contributor; marked a Work read, refreshed and observed persisted read state, then returned it to unread.
- Collections: at both viewports, submitted a whitespace-only name and received the expected `INVALID_REQUEST`; created the unordered private collection `Избранное`; added two different Works and observed `2 visible works`; refreshed and retained both memberships; renamed it to `Осеннее чтение`; enabled ordered mode, saved, refreshed, and retained both the ordered flag and stable Work order; removed one Work and observed `1 visible work`; then deleted the collection and confirmed the editor and membership controls disappeared without stale UI.
- Optimistic concurrency: opened the same collection in two built-in-browser tabs, renamed it in tab A, and submitted a stale rename from tab B without refreshing. Tab B displayed `The personal library state changed. Reload it and try again. (PERSONAL_VERSION_CONFLICT)`, and a reload confirmed tab A's name remained authoritative.
- Authentication and keyboard: visible `2.4px` focus outlines were present on focused controls. Enter activated sign-in, Space activated the explicit keyboard sign-in control, and immediate sign-out/sign-in hid private workspace data while signed out and restored the persisted favorite after authentication.
- Diagnostics: the console contained only the permitted Electron development CSP warning. Network inspection recorded the expected empty-name `400`; the conflict UI returned its expected version-conflict response in the second tab. One deliberate malformed-credential keyboard attempt returned `401`; after correcting the input and after the CSRF fix, authentication and all Yurlib requests completed without unexpected errors.
- Final personal state: zero favorites, zero read Works, and no private collections. Angular and Compose services were stopped; `.local/proxy.visual.json` and the temporary credential were removed; the named PostgreSQL volume remains; no volumes were deleted.

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
- `./mvnw -pl services/yurlib-server -Dtest=InternalWorkerSecurityConfigurationTest,OwnerRecoveryCommandTest test` — passed; two focused tests cover the non-web recovery context and forced recovery arguments.
- Re-ran the documented owner-recovery command against the retained local database after the fix — passed.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed after the sign-out session-refresh fix; 15 tests.
- `npm --prefix web/yurlib-web run build` — passed after the final frontend change.
- `./mvnw -pl services/yurlib-server -DskipTests verify` — passed after the recovery fix; compilation, packaging, PMD, and SpotBugs are clean with zero findings.
- Final `git diff --check` — passed.

## Blockers

- None. Automated verification and the pull request's desktop/mobile manual acceptance are complete.
