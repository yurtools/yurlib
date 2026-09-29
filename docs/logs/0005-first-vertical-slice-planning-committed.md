# First Vertical Slice Planning

- Status: Committed
- Started: 2026-09-29
- Initial branch: `dev`
- Repository: `yurtools/yurlib`

## Prompt

> proceed. also fix the app.html issue

## Approved direction

The prompt approves the recommended next-step design from the preceding exchange:

- merge the current reviewed-skill changes before product work;
- plan the first local-library walking skeleton under issue #3;
- record the M1 constraints and trade-offs;
- convert the design into implementation-ready GitHub issues and project items;
- close the completed planning and bootstrap-parent issues;
- fix the hard-coded backend health URL in the Angular shell.

## Plan

1. Correct the Angular shell to use a deployment-neutral relative health URL and add a regression test.
2. Verify, commit, and push the correction on `dev`; open a pull request for the accumulated post-bootstrap changes and merge it through protected `main` after required checks pass.
3. Create a short-lived issue #3 planning branch from the updated `main`.
4. Add an accepted ADR for the first-slice execution, storage, access, and format constraints.
5. Add a local-library vertical-slice design covering components, data flow, interfaces, persistence, security, failures, acceptance, and implementation order.
6. Update architecture indexes/status documents and turn the design into linked GitHub issues in the Yurlib Engineering project.
7. Update and close issue #3 and parent issue #11 when their acceptance criteria are satisfied.
8. Run relevant verification, commit and push the planning branch, open its pull request, and record CI evidence.

## Execution log

### 2026-09-29 — Preflight

- Confirmed `dev` was clean and synchronized with `origin/dev` at `2d067c1`.
- Confirmed no open pull request currently targets the accumulated `dev` changes.
- Confirmed issue #3 and parent issue #11 are the only open bootstrap issues.
- Confirmed the Angular 22.2 shell hard-coded `http://localhost:8080/actuator/health`.
- Read the complete `system-design`, `architecture-decision`, and `angular-developer` skill instructions before making changes.

### 2026-09-29 — Angular health-link correction

- Replaced the absolute `http://localhost:8080/actuator/health` link with deployment-neutral `/actuator/health`.
- Added an Angular regression test that locates the backend-health link and requires its raw `href` to equal `/actuator/health`.
- `npm --prefix web/yurlib-web test -- --watch=false`: one test file and three tests passed.
- `npm --prefix web/yurlib-web run build`: production build completed successfully.
- `git diff --check`: passed.

### 2026-09-29 — Post-bootstrap merge

- Committed the Angular correction and this active log as `095b752` (`Fix deployment-neutral backend health link`) and pushed `dev`.
- Opened pull request #14, `Add reviewed frontend skills and fix health link`, for the accumulated post-bootstrap audit, skill, documentation, and Angular changes.
- Required `Backend`, `Frontend`, `Compose Configuration`, and `Dependency Review` pull-request checks passed.
- Squash-merged pull request #14 into protected `main` as `92e7a25ccb243d6f35ec69f37dd2f2bf68fd7f72` and deleted remote `dev`.
- Created short-lived branch `feature/3-local-library-slice-plan` from the updated `origin/main`.

### 2026-09-29 — Walking-skeleton design

- Added accepted ADR-0003 for one verified read-only root, allowed mount aliases, an identity marker, plain EPUB/FB2/MOBI scope, PostgreSQL-backed jobs, a bounded in-process worker, polling, loopback-only development bypass, required shared-network owner authentication, and asset-ID downloads.
- Added `docs/architecture/local-library-vertical-slice.md` with scope, component boundaries, filesystem trust model, conceptual data model, job lifecycle, recovery, extraction safety, Angular flow, observability, acceptance matrix, implementation order, and definition of done.
- Added `contracts/openapi/yurlib-v1.yaml` with five initial root, scan, job, catalog, and download paths plus RFC 9457 Problem Details schemas.
- Updated the ADR index, development-environment version 0.8, product-concept version 0.5 and D-03 split, and README project status.

### 2026-09-29 — GitHub backlog

- Created milestone #2, `M1 — Local Library`.
- Added `m1`, `ingestion`, and `catalog` labels.
- Created implementation issues #15, #16, #20, and #22. Four parallel creations returned transient GraphQL errors; retrying them sequentially created #23 through #26.
- Created parent delivery issue #27 with the ordered checklist #20, #23, #15, #16, #24, #25, #26, and #22.
- Added all nine M1 issues to the public `Yurlib Engineering` project.
- Updated issue #3 with the approved artifacts and complete implementation backlog and moved it to `In Progress` pending merge of the planning pull request.

### 2026-09-29 — Planning pull request

- Committed the accepted design, OpenAPI contract, architecture updates, backlog references, and this process log as `51622c9` (`Plan first local-library vertical slice (#3)`).
- Pushed `feature/3-local-library-slice-plan` and opened pull request #28, `Plan first local-library vertical slice`.
- Configured pull request #28 to close planning issue #3 and bootstrap parent issue #11 when it is merged.
- Required CI run `36580650177` passed: `Backend`, `Frontend`, `Compose Configuration`, and `Dependency Review`.

## Verification

- Angular unit tests: one file and three tests passed.
- Angular production build: passed.
- OpenAPI YAML: parsed successfully as OpenAPI 3.1 with five paths.
- `git diff --check`: passed before the planning commit.
- Pull-request CI run `36580650177`: all four required checks passed.

## Result

The bootstrap merge, Angular correction, approved design, initial API contract, M1 implementation backlog, planning commit, pull request, and required CI verification are complete. Pull request #28 is ready for its protected-branch squash merge.
