# Library Folder Usability

- Status: In progress
- Issue: #97
- Branch: `fix/97-library-folder-usability`

## Initiating user prompt

> so, those settings need to be better arranged in UI for usability. Its imposible to understand. Also, trying to convert fb2 to epub I get An accessible default managed conversion root is required. (MANAGED_ROOT_UNAVAILABLE)

## Operational plan

1. Separate book folders and generated output in a dedicated settings view; hide completed setup and replace storage jargon with task-based actions.
2. Add a documented, source-management-authorized API to select an existing available output folder as the cover or conversion default, atomically replacing the previous default.
3. Make missing conversion output actionable, test UI and backend behavior, and exercise conversion with the approved isolated worker.
4. Restart the local app and leave it ready for user testing.

## UI direction

- Retain the existing paper, green accent, and typography; use simple folder rows and clear section headings.
- Keep browsing as the primary workspace. Settings contains book folders, generated output, then an explicit add-folder form.
- Use existing button/focus transitions and restrained section entry; preserve reduced-motion behavior.

## Actions and findings

- Existing output folder is available but neither output-default flag is selected.
- The current UI only sets defaults on folder creation and leaves the creation form visible after setup.
- Created issue #97 with acceptance criteria and a short-lived branch, preserving the existing Angular configuration change and prior startup log.
- Added issue #97 to Yurlib Engineering and set its status to In Progress.
- Added a Library settings toggle, separate book/output folder lists, source selection for scanning, explicit add-folder actions, plain field labels, optional subfolder disclosure, verification guidance, automatic focus on folder creation, and focus restoration on cancellation.
- Added `PUT /api/v1/library-roots/{rootId}/defaults` with `COVERS` or `CONVERSIONS` purpose. Existing source-management authorization and CSRF apply. PostgreSQL row locking and a transaction replace only the selected default, preserving folder identity, existing assets, and the other output purpose. Source, missing, and unavailable targets are rejected.
- Added conversion preflight guidance and a settings link for source managers, plus non-disclosing owner-contact guidance for other accounts when storage access fails.
- Selected the existing managed folder as both conversion and cover destination through the UI, then confirmed both persisted flags through the API.
- Restarted the local backend on Java 25 and port 8082 with a private worker token in ignored local configuration. Frontend remains at port 4200 and PostgreSQL at port 5433.
- Started the existing approved worker image `yurlib_visual_76_retest-document-worker:latest` on an internal network with a read-only filesystem, UID 10001, dropped capabilities, no source/output mounts, 64 PIDs, one CPU, 2 GiB memory, and a bounded temporary work filesystem.
- Docker Desktop could not directly reach the host's loopback development listener. A temporary local test relay exposes only authenticated `/internal/v1/` requests to the isolated worker and forwards them to the existing backend. This ignored runtime harness is not a product service or Compose change. Its Python container has no published port; only the relay has a host connection, and the worker remains on the internal network alone.
- The in-app browser runtime reported no available browser. Used an isolated headless Chrome test session with temporary Playwright tooling under ignored `.local/` for UI verification. Screenshots and downloaded book content remain local and untracked.

## Verification

- Frontend tests: 24 passed, including completed-setup hiding, multiple-source selection, missing-output prevention and repair, failed output selection, and verification-code clearing/focus on cancel.
- Production frontend build after keyboard refinements: passed (396.12 kB initial bundle).
- Initial focused backend run: all behavior/security/PostgreSQL tests passed; the explicit contract-path inventory needed the new additive endpoint added. Corrected the inventory.
- Full `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw verify`: server tests completed with 180 tests, zero failures/errors, one existing skip, and coverage checks passed. Root formatting and API compatibility checks passed. PMD reported two simplifiable Boolean expressions in default replacement; simplified them.
- After that correction, the PostgreSQL default-switching regression passed again. `./mvnw -pl services/yurlib-server verify -DskipTests` passed packaging, coverage, PMD, and SpotBugs without rerunning the already successful full server suite. `./mvnw -pl services/yurlib-document-worker verify` passed all nine worker tests and quality checks. All Maven commands used the installed Java 25 via `JAVA_HOME`.
- Final browser checks at 1440×900 and 390×844 confirmed both destinations persist, setup is absent from normal browsing, no horizontal overflow, input focus on opening Add folder, restored focus on cancellation, and zero browser page errors. Screenshots were inspected locally.
- Two FB2 requests succeeded through the app. The second was observed progressing from QUEUED to SUCCEEDED through the actual worker. Downloads passed EPUB mimetype/container and ZIP integrity checks. Original download SHA-256 values were unchanged before/after conversion. No private titles, contents, or token values are recorded here.
- `docker compose config --quiet` and `git diff --check`: passed.

## Blockers and delivery references

- None. No commit or pull request yet.
