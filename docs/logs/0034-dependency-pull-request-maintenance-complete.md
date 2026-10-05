# Dependency Pull Request Maintenance

- Status: Complete
- Started: 2026-10-05
- Repository: `yurtools/yurlib`
- Branch: `chore/angular-22-2-1`
- Consolidated pull request: [#84](https://github.com/yurtools/yurlib/pull/84)
- Squash commit: `d65addf649d8f978e69dd1cee6755cb8bd3617e4`

## Prompt

> merge whats required, handle other pending PRs

## Plan

1. Confirm the successful owner acceptance for pull request #76, restore its checklist and issue linkage, and squash-merge it after required checks pass.
2. Audit every other open pull request and inspect failed checks before deciding whether to merge, repair, consolidate, or close it.
3. Update each independent dependency pull request onto current `main`, require fresh successful checks, and squash-merge it.
4. Consolidate the interdependent Angular 22.2.1 patch updates into one peer-compatible change, verify it, and close the split Dependabot pull requests as superseded.
5. Preserve the unrelated local Angular analytics preference and update process-log lifecycle states.

## Actions and results

- Restored pull request #76's full description and checked its owner visual-acceptance item using the reported desktop and mobile retest at commit `c9f7f97c020825c4388dca490fb74f592f0f7398`.
- Waited for all required checks, then squash-merged pull request #76 as `421b72c3f90270404133aab6c3fd5a77ff7efc50`; its `Closes #61` linkage closed issue #61.
- Updated and freshly verified pull request #77, the Maven wrapper 3.9.11 to 3.10.0 update, then squash-merged it as `48f9837bf5c17707e4b2f04ed4b3b8f5adaa02f8`.
- Updated and freshly verified pull request #78, the OpenAPI Diff Maven plugin 2.1.4 to 2.1.7 update, then squash-merged it as `0354445d88d4c563b396d7589ac8da56bf7dacbb`.
- Updated and freshly verified pull request #83, the Vitest 5.0.2 to 5.0.3 update, then squash-merged it as `63ea3647517dbc665e3d1e8bd6f1361c563ec376`.
- Investigated pull requests #79 through #82. Their frontend checks failed because each changed only one Angular package while Angular's peer contracts require the framework packages to use the same patch version.
- Updated the complete Angular runtime and build-tool set from 22.2.0 to 22.2.1 together: common, compiler, core, forms, platform-browser, router, build, CLI, and compiler CLI.
- Renormalized `mvnw.cmd` after the Maven wrapper update committed CRLF bytes contrary to the repository's text-normalization rules, preventing a false dirty worktree on Linux without changing script content.
- Preserved the unrelated, unstaged `web/yurlib-web/angular.json` analytics preference throughout the work.
- Renamed the completed M2 acceptance log from `pr-open` to `merged` and recorded pull request #76's squash commit.
- Opened consolidated pull request #84, waited for all protected checks, and squash-merged it as `d65addf649d8f978e69dd1cee6755cb8bd3617e4`.
- Closed split Angular pull requests #79 through #82 as superseded by #84. Dependabot concurrently auto-closed #80 after detecting that compiler CLI was current; the superseding explanation was still recorded on it.
- Confirmed that no pull requests remain open.

## Verification

- GitHub Actions run `37326523272` for pull request #77 — passed all required checks.
- GitHub Actions run `37326931602` for pull request #78 — passed all required checks.
- GitHub Actions run `37327458221` for pull request #83 — passed all required checks.
- `npm --prefix web/yurlib-web ci` — passed with 267 packages and zero vulnerabilities; npm retained its existing warning about four blocked optional/native install scripts.
- The generated lockfile resolves all nine Angular runtime and build packages to 22.2.1.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed, 19 tests.
- `npm --prefix web/yurlib-web run build` — passed, 384.39 kB initial bundle.
- `docker compose config --quiet` — passed.
- `./mvnw --version` — passed with Apache Maven 3.10.0 after wrapper renormalization.
- `git diff --check` — passed.
- GitHub Actions run `37329112513` for pull request #84 — passed Backend, Frontend, Compose Configuration, and Dependency Review.

## Blockers

- None.
