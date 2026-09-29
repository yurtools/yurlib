# Maven quality gates

- Status: Committed
- Started: 2026-09-29
- Branch: `chore/31-maven-quality-gates`
- Issue: [#31](https://github.com/yurtools/yurlib/issues/31)
- Repository: `yurtools/yurlib`

## Initiating prompt

> treat it as the next task and implement. use good readable code formatting

## Plan

1. Track the work in GitHub issue #31 and the repository project.
2. Add deterministic Maven formatting, compiler linting, supported toolchain enforcement, and a Maven Wrapper.
3. Replace Maven defaults with a repository-owned PMD ruleset, security-aware SpotBugs analysis, and JaCoCo minimums.
4. Format the existing project-owned sources and configuration with readable, pinned formatters.
5. Make CI output diagnostic logs and retain backend analysis reports.
6. Update contributor and environment documentation with the enforced commands.
7. Run the complete backend, frontend, and Compose verification baseline.
8. Commit, push, open a pull request, and record the resulting references.

## Execution log

### 2026-09-29 — Preflight

- Created GitHub issue #31, added it to the Yurlib project, and moved it to `In Progress`.
- Created and checked out branch `chore/31-maven-quality-gates` from the latest `main`.
- Reviewed the effective Maven configuration and established the current JaCoCo baseline before choosing non-regressive thresholds.
- Confirmed the existing backend verification passes but relies on default PMD rules, has no formatting check, has no JaCoCo minimum, and does not enable compiler lint explicitly.

### 2026-09-29 — Implementation

- Added Maven Wrapper 3.9.11 and Enforcer rules that require Maven 3.9.11 through Maven 3.x and JDK 25 or 26.
- Added Spotless 3.10.3 with pinned Palantir Java Format 2.98.0 and Prettier 3.9.9. The root Maven gate now checks project-owned Java, POM, Markdown, YAML, and JSON formatting while excluding vendored skill reviews and historical process logs.
- Applied the pinned formatters to the existing project-owned files and documented `./mvnw spotless:apply` as the correction command.
- Enabled all Java compiler lint warnings and fixed the resulting missing `serialVersionUID` warning.
- Added `config/pmd/yurlib-ruleset.xml`, upgraded the effective PMD engine to 7.28.0, and removed explicit JavaScript and JSP engine overrides.
- Added FindSecBugs 1.14.0 to maximum-effort, low-threshold SpotBugs analysis. Added a documented narrow filter for FindSecBugs endpoint markers and the correlation header that is already parsed and re-emitted as a canonical UUID.
- Added JaCoCo bundle minimums of 65% line coverage and 50% branch coverage. The measured baseline remains 67.2% line and 52.6% branch coverage.
- Preserved caught causes while correcting PMD findings in filesystem and persistence error translations; documented the intentional empty JPA constructor.
- Updated CI to use the Maven Wrapper, provision Node.js for Prettier, limit token permissions by job, cancel superseded pull-request runs only, and retain the Maven log and analyzer reports for seven days.
- Updated contributor, API-contract, README, and development-environment commands and decisions to use the wrapper and describe the enforced quality gates.

### 2026-09-29 — Corrections found by verification

- Restored the explicit matching `pmd-core` override after a mixed PMD 7.17/7.28 plugin classpath caused an API incompatibility.
- Corrected eight findings exposed by the maintained PMD quickstart and security rules, including literal-first comparisons, exception-cause preservation, and the intentional JPA constructor comment.
- Added the narrow FindSecBugs exclusion filter after its first run reported three informational Spring endpoint markers and one false positive for the canonicalized correlation header.

## Verification

- `./mvnw -B verify` — passed; formatter and toolchain enforcement, OpenAPI compatibility, compiler lint, 28 tests, JaCoCo minimums, PMD 7.28.0, SpotBugs, and FindSecBugs all passed with zero unfiltered findings.
- `npm --prefix web/yurlib-web ci` — passed; 267 packages installed and 0 vulnerabilities reported.
- `npm --prefix web/yurlib-web test -- --watch=false` — passed; 3 tests.
- `npm --prefix web/yurlib-web run build` — passed.
- `docker compose config` — passed.
- `git diff --check` — passed.

## Blockers

None. Maven itself emits JDK 26 forward-compatibility warnings from its Guice/Sisu runtime, PMD notes that the active JDK supplies `jrt-fs.jar`, and Mockito notes future dynamic-agent restrictions; none is a project-source violation or failed gate.

## References

- Issue: <https://github.com/yurtools/yurlib/issues/31>
- Branch: `chore/31-maven-quality-gates`
- Implementation commit: `caa712c` (`chore: enforce Maven quality gates (#31)`)
- Pull request: [#32](https://github.com/yurtools/yurlib/pull/32)
