# Initial Environment Setup

- Status: Committed
- Started: 2026-09-28
- Branch: `dev`
- Repository: `yurtools/yurlib`

## Prompt

> create a folder docs/logs. Remember, this folder will be there to capture the exact proccess and prompts that are executed. Each file should start with a 4 digit number, meaningful name and status, like 0001-intitial environment-setup-committed.md , contain prompt and plan.
> then proceed with setup and bootstrap. Update steps in those logs.

## Logging convention

Every file in `docs/logs/` must:

- begin with a four-digit sequence number;
- include a meaningful kebab-case description;
- end with a lifecycle status such as `in-progress`, `blocked`, `committed`, or `complete`;
- contain the initiating user prompt verbatim;
- contain the operational plan;
- record commands and externally visible actions without secrets;
- record results, verification evidence, blockers, and commit or pull-request references.

Logs capture operational plans and executed actions. They do not contain hidden reasoning, credentials, tokens, or other secrets.

## Plan

1. Record and verify the GitHub Issues and Projects decision in ADR-0001 and all architecture documents.
2. Add repository governance files, issue forms, pull-request guidance, and an ADR template.
3. Create the GitHub engineering issues and configure a GitHub Project when authorization permits.
4. Record the initial deployable-topology ADR needed for the bootstrap structure.
5. Review and install the approved project skills according to the Yurlib quarantine policy.
6. Bootstrap the Spring Boot backend, Angular frontend, PostgreSQL Compose environment, migrations, tests, architecture checks, and observability foundation.
7. Add GitHub Actions for deterministic backend, frontend, contract, and security checks.
8. Run local verification, update this log with exact results, commit and push `dev`, open a pull request to `main`, and add successful CI checks to branch protection.

## Execution log

### 2026-09-28 — Started

- Confirmed the working branch is `dev` at commit `5e099ee`.
- Confirmed the worktree already contains the accepted ADR-0001 documentation changes.
- Confirmed GitHub Issues and Projects are enabled for `yurtools/yurlib`.
- Retrieved the finalized Yurlib decisions for name, GPL-3.0-only licensing, workstation toolchains, and GitHub-based work management.
- Read the `skill-installer` instructions before any third-party skill installation.

### 2026-09-28 — Repository governance

- Added `AGENTS.md`, `.editorconfig`, `.gitattributes`, and repository ignore rules.
- Added pull-request guidance, backend/frontend/documentation instructions, and structured issue forms.
- Added the ADR index and reusable ADR template.
- Accepted ADR-0001 for GitHub Issues and Projects and ADR-0002 for a modular monolith.
- Enabled GitHub Issues and Projects on `yurtools/yurlib`.
- Added engineering, bootstrap, architecture, backend, frontend, infrastructure, security, and documentation labels.
- Created milestone `Engineering Environment` and issues #1 through #11 for the complete setup backlog.
- GitHub Project creation remains blocked because the current GitHub CLI token does not have `read:project` and `project` scopes. The milestone and linked parent issue preserve the setup plan until that authorization is granted.

### 2026-09-28 — Agent skill review and installation

- Bootstrapped `skill-security-review` outside the repository at reviewed commit `d19fd5eff9762bada5ab2f18b0417f7e8d3f4fef`.
- Manually inspected its instructions, scanner, rule catalog, templates, package surface, and symlinks without installing dependencies.
- Ran `node tests/smoke.mjs`; all 17 scanner regression checks passed.
- Cloned every candidate into ignored `_skill-review/`, recorded each source commit, statically scanned the exact target skill, and manually reviewed its complete `SKILL.md` plus associated executable examples.

| Installed skill | Source commit | Scan result |
|---|---|---|
| `system-design` | `e02ec7e226a6e4f8419fd3b88a1d8e472d421b32` | Markdown-only; no findings |
| `architecture-decision` | `e02ec7e226a6e4f8419fd3b88a1d8e472d421b32` | Markdown-only; no findings |
| `java-spring-best-practices` | `3dc9067c75242c94997ef3cb4a8bdca043ffb928` | No executable surface; no findings |
| `spring-boot-testing` | `99d50948fbc18bce6107dafc74a92e86634acbc2` | Markdown-only; no findings |
| `github-actions-author` | `612a1821b6299be020a78d349455252bd1d577bb` | Markdown-only; one false-positive static-text finding for a Codecov token description |

- Installed the approved copies into `.agents/skills/` from their reviewed local clones with `npx skills add ... --agent codex --copy --full-depth -y`.
- Reviewed OpenAI Codex commit `e07e58c8429019de78b138d7138deaaf7f3ef22c`. The documented `code-review` skill was absent and the available `review-agent` has different semantics, so it was not silently substituted and remains deferred.

### 2026-09-28 — Application and CI bootstrap

- Generated Angular `yurlib-web` with Angular CLI 22.2.0 and npm 12, then replaced the starter page with a minimal Yurlib shell and tests.
- Added the Maven reactor and Spring Boot 4.1.1 `yurlib-server` module.
- Added the system endpoint, Actuator configuration, graceful shutdown, and virtual-thread configuration.
- Added PostgreSQL 18 Compose configuration, environment-variable defaults, Flyway migration V1, and a Testcontainers migration test.
- Added ArchUnit 1.5.1, JaCoCo 0.8.15, PMD 7.28.0 through Maven PMD Plugin 3.28.0, and SpotBugs Maven Plugin 4.10.4.1.
- Kept JDK 26 as the build/runtime tool and targeted Java 25 bytecode because Java 25 is the current supported LTS target across the selected bootstrap tooling.
- Added a SHA-pinned GitHub Actions workflow with `Backend`, `Frontend`, `Compose Configuration`, and pull-request-only `Dependency Review` jobs.
- Added weekly Dependabot configuration for Maven, npm, and GitHub Actions; enabled repository vulnerability alerts and automated security fixes. Secret scanning and push protection were already enabled.
- Updated README and architecture documents to describe the actual modular-monolith layout and local commands.

### 2026-09-28 — Corrections found during verification

- Replaced Spring Initializr's metadata identifier `4.1.1.RELEASE` with the published Maven version `4.1.1`.
- Updated the Spring Boot 4 `WebMvcTest` import to its current package.
- Upgraded ArchUnit from 1.4.1 to 1.5.1 for JDK 26 compatibility.
- Overrode the Maven PMD Plugin's bundled PMD 7.17.0 modules with PMD 7.28.0 for JDK 26 compatibility.

### 2026-09-28 — Commit, pull request, and protection

- Committed the bootstrap as `f4fed48` (`Bootstrap Yurlib engineering environment`).
- Pushed `dev` to `origin` and set its upstream.
- Opened pull request #12, `Bootstrap Yurlib engineering environment`, against protected `main`.
- GitHub Actions push run `36476856389` and pull-request run `36476878844` completed successfully.
- Added the registered `Backend`, `Frontend`, `Compose Configuration`, and `Dependency Review` contexts as strict required checks on `main`.
- Preserved the existing pull-request requirement, administrator enforcement, stale-review dismissal, conversation resolution, linear history, and force-push/deletion restrictions.

## Verification

- `node tests/smoke.mjs` in the trusted scanner: 17/17 passed.
- `npm ci`: completed; 268 packages audited, 0 vulnerabilities.
- `npm test -- --watch=false`: 1 file and 2 tests passed.
- `npm run build`: production build completed successfully.
- `docker compose config`: configuration valid.
- `mvn -B clean verify`: 3 backend tests passed; Flyway migration ran against PostgreSQL 18; ArchUnit, PMD, SpotBugs, and JaCoCo completed; build succeeded.
- Final `mvn -B verify`: succeeded after dependency caches were warm.
- `git diff --check`: passed after final documentation cleanup.

## Result

Implementation, local verification, push, pull request, CI registration, and branch-protection setup are complete. Pull request #12 is ready for human review. GitHub Project creation separately requires expanded token scope.
