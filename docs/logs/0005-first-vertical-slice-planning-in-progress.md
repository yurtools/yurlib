# First Vertical Slice Planning

- Status: In progress
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

## Verification

Pending.

## Result

In progress.
