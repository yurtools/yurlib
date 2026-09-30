# Current Status — v0.1.0-m1

- Status: Complete
- Date: 2026-09-29
- Release: `v0.1.0-m1`
- Milestone: M1 — Local Library
- Repository: `yurtools/yurlib`

## Prompt

> Create "current status" document in logs, list all implemented features there plus suggest manual testing steps to get usr feedback. rename 0015 to merge, close what needs to be closed. assign release tag, i.e. v0.1.0-m1
> Create a "Design M2" step in issues with M2 design doc as an expected outcome

## Plan

1. Synchronize the protected `main` branch after pull request #40 and record the completed M1 state.
2. Rename the owner-access process log to its merged lifecycle status and update stale project-status text.
3. Document the implemented product, engineering, security, and acceptance capabilities in M1.
4. Provide a repeatable manual feedback workflow that does not expose credentials, private book content, or physical library paths.
5. Close the M1 parent issue and milestone, mark the Project item Done, and tag the merged release as `v0.1.0-m1`.
6. Create an M2 milestone and a tracked Design M2 issue whose required outcome is an approved architecture document and implementation backlog.

## Implemented features

### Product workflow

- One deployment-configured, read-only library root selected through a server-advertised mount alias and normalized relative path.
- Private root identity marker verification; only its SHA-256 digest is persisted.
- Manual, durable scan requests that return immediately with a job identifier.
- PostgreSQL-backed scan jobs with queued/running/terminal states, counters, safe file failures, leases, heartbeat recovery, and restart reclamation.
- Bounded filesystem discovery that does not follow symbolic links and rejects paths outside the configured root.
- Plain EPUB, FB2, and MOBI discovery and bounded metadata extraction.
- Stable-file checks before catalog publication and before original-asset download.
- Incremental unchanged-file detection, idempotent reconciliation, unavailable-root protection, and no deletion inference after incomplete coverage.
- Persistent provisional Work, Edition, Asset, Asset Location, and Metadata Observation records.
- Catalog search by observed title, contributor, filename, or identifier with bounded pagination.
- Original ebook download through an opaque asset identifier rather than a client-supplied path.

### Web experience

- Responsive Angular workbench for desktop and mobile.
- Initial loading, owner sign-in, invalid-credential, authenticated, session-expiry, and sign-out states.
- Keyboard-operable sign-in with visible focus, masked password entry, password clearing, and a pristine form after sign-out.
- Authenticated owner identity and backend-health access in the header.
- Root configuration, configured-source status, scan start, progress counters, safe failure display, catalog search, empty/results states, pagination, and original-download actions.
- Deployment-neutral relative API and actuator URLs.

### Access and security

- Spring Security session authentication for one deployment-defined owner.
- Angular-compatible cookie/header CSRF protection for state-changing requests.
- Owner-only M1 API and sensitive actuator access with generic correlated Problem Details.
- Fail-closed owner mode when deployment credentials are absent.
- Explicit unauthenticated `loopback-dev` profile guarded against wildcard or non-loopback binding.
- Secure, HTTP-only, SameSite session-cookie defaults; shared-network deployments require TLS.
- Compose publishes application and database ports on host loopback by default and mounts the library read-only.
- Regression coverage for traversal, escaping symlinks, XXE, unsafe archive entries, decompression bounds, unstable files, unsafe filenames, and source preservation.

### Operations and engineering

- Spring Boot modular-monolith backend, Angular frontend, PostgreSQL 18, Flyway migrations, Maven Wrapper, and Docker Compose development deployment.
- Versioned OpenAPI contract with a checked compatibility baseline.
- Structured scan lifecycle logs and Micrometer queue, job, duration, file, and bounded parser-failure metrics without physical paths or credentials.
- Generated or licensed EPUB/FB2/MOBI and hostile-input fixtures; no private books are committed.
- CI gates for backend verification, frontend tests/build, Compose validation, and dependency review.
- Formatting, JaCoCo coverage, PMD, SpotBugs, architecture, controller, persistence, parser-security, and PostgreSQL/Testcontainers acceptance checks.
- GitHub Issues and Projects workflow, protected `main`, squash-merge delivery, ADRs, architecture documents, project skills, and auditable process logs.

## Automated acceptance evidence

- `./mvnw verify`: 88 tests plus formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs gates.
- Angular: 9 tests and a production build.
- Compose configuration validation and dependency review.
- PostgreSQL walking-skeleton test: configure root, scan four valid format fixtures, query the catalog, read original bytes, repeat an unchanged scan with zero reparses, and verify source hashes remain unchanged.
- Human visual acceptance passed at 1440×900 and 390×844 without overflow, clipping, unusable spacing, or Yurlib console errors.

## Suggested manual testing for user feedback

Use generated, openly licensed, or disposable sample books. Do not attach private books, marker tokens, credentials, or physical filesystem paths to feedback.

### 1. Prepare a disposable source

```bash
mkdir -p .local/library
export YURLIB_TEST_ROOT_TOKEN="$(openssl rand -hex 24)"
printf '%s\n' "$YURLIB_TEST_ROOT_TOKEN" > .local/library/.yurlib-root-id
```

Place a small mix of plain `.epub`, `.fb2`, and `.mobi` samples under `.local/library`, including nested folders and one deliberately malformed disposable file.

### 2. Start Yurlib

```bash
export YURLIB_OWNER_PASSWORD='choose-a-disposable-local-password'
docker compose up -d --build
npm --prefix web/yurlib-web ci
npm --prefix web/yurlib-web start
```

If the default PostgreSQL or backend host ports are unavailable, use alternate host ports instead:

```bash
export YURLIB_DB_PORT=55432
export YURLIB_SERVER_PORT=18080
docker compose up -d --build
npm --prefix web/yurlib-web ci
sed 's|http://localhost:8080|http://localhost:18080|g' \
  web/yurlib-web/proxy.conf.json > .local/proxy.manual.json
npm --prefix web/yurlib-web start -- \
  --proxy-config ../../.local/proxy.manual.json
```

With these overrides, the backend health endpoint is `http://localhost:18080/actuator/health`. The browser URL remains `http://localhost:4200/`. The database container still listens on port 5432 inside the Compose network; only its workstation host port changes.

Open `http://localhost:4200/`, sign in as `owner`, and keep the terminal output available for safe correlation identifiers. Never paste the password or root token into an issue.

### 3. Exercise the primary journey

1. Try one incorrect password, then sign in successfully using only the keyboard.
2. Configure the `main` mount with an empty relative path and the disposable marker token.
3. Start a scan and observe queued, running, counters, completion, and malformed-file feedback.
4. Search by a title, contributor, filename fragment, and identifier from the sample corpus.
5. Page through results when the corpus is large enough.
6. Download an original and compare it byte-for-byte with the disposable source.
7. Run the same scan again and confirm it completes without creating duplicate catalog entries.
8. Restart the server, sign in again, and confirm the configured source and catalog persist.
9. Sign out and confirm protected workspace data is no longer visible.
10. Repeat the core navigation at desktop and narrow mobile widths using keyboard-only operation where practical.

### 4. Capture useful feedback

For each observation, record:

- the task you attempted and why;
- expected and actual behavior;
- whether it blocks use, causes confusion, or is only cosmetic;
- browser, operating system, viewport, and approximate disposable corpus size;
- the safe correlation identifier and scan/job identifier when available;
- a redacted screenshot when visual context matters;
- suggested wording or workflow changes, if any.

Useful feedback questions include:

- Was first-run setup understandable without reading source code?
- Did the identity-marker concept and error guidance make sense?
- Could you tell whether a scan was making progress and whether failures were actionable?
- Were provisional titles/contributors and search results useful enough to find a book?
- Did original download behavior feel trustworthy and predictable?
- Which curation action did you most expect to find next?

### 5. Clean up

```bash
docker compose down
rm -f .local/proxy.manual.json
unset YURLIB_DB_PORT YURLIB_SERVER_PORT YURLIB_OWNER_PASSWORD YURLIB_TEST_ROOT_TOKEN
```

Delete disposable source material only when it is no longer needed. The PostgreSQL volume is intentionally retained unless the tester explicitly chooses to remove it.

## Known M1 boundaries

- One active read-only root; no source reorganization or writes.
- Plain EPUB, FB2, and MOBI only; `.fb2.zip` and archive ingestion are deferred.
- Manual scans and polling; no scheduled scan, filesystem watcher, WebSocket, or server-sent-event updates.
- Provisional observed metadata; no curation editing, aliases, merges, tags, collections, or duplicate review yet.
- No format conversion, managed output storage, connectors, AI workflow, or multi-user accounts.

## Results

- Pull request #40 merged as `0aec9dd`; issue #22 closed with green required checks.
- Parent issue [#27](https://github.com/yurtools/yurlib/issues/27) is closed, its Project item is Done, and milestone #2 is closed with zero open issues.
- Milestone #3, `M2 — Curation`, and Design M2 issue [#41](https://github.com/yurtools/yurlib/issues/41) were created. Issue #41 is in Project status Todo and requires an approved `docs/architecture/m2-curation-design.md` plus an ordered implementation backlog before M2 development begins.
- Release tag `v0.1.0-m1` is assigned to the protected `main` closure commit after this documentation passes its pull-request gate.

## Executed actions

- Fast-forwarded local `main` to merged M1 commit `0aec9dd` and created branch `docs/27-complete-m1-release`.
- Renamed process log 0015 from `pr-open` to `merged` and recorded the squash-merge result.
- Updated the README project status and marked the M1 vertical-slice document implemented.
- Created the M2 label, milestone, and Design M2 issue, then added the issue to the Yurlib Engineering Project in Todo.
- Closed the M1 parent issue, moved its Project item to Done, and closed the M1 milestone.
- Opened protected-branch pull request [#42](https://github.com/yurtools/yurlib/pull/42) for the release-status documentation.

## Verification and blockers

- Documentation formatting, repository diff checks, pull-request CI, final tag target, and remote references are verified before release handoff.
- No blocker is known. The release tag is intentionally created only after the closure documentation reaches protected `main`.
