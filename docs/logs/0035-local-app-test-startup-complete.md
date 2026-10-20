# Local App Test Startup

- Status: Complete
- Started: 2026-10-20
- Commit or pull request: None; local runtime setup only.

## Initiating user prompt

> start the app and I will test it

## Operational plan

1. Inspect existing services and preserve existing local configuration and source assets.
2. Start the existing PostgreSQL Compose service on an available loopback port.
3. Start the backend with the loopback development profile and the frontend with a local proxy override.
4. Verify frontend availability, backend health, and development access, then provide the testing URL.

## Executed commands and actions

- Read repository instructions, Compose configuration, and local development settings.
- Checked `git status --short`, `docker compose ps`, `docker ps`, and listening ports.
- Existing unrelated applications occupy ports 5432, 8080, and 8081. Selected 5433 for the database and 8082 for the backend; port 4200 is available for the frontend.
- Preserved the existing local change to `web/yurlib-web/angular.json`.
- Ran `YURLIB_DB_PORT=5433 docker compose up -d --wait postgres`; existing Yurlib PostgreSQL data was retained.
- Prepared library identity markers only where absent, without replacing existing marker files or source assets.
- Wrote ignored `.local/proxy.test-startup.json` to route `/api` and `/actuator` to `http://127.0.0.1:8082`.
- Initial backend launch rejected the workstation's default Java 27. Selected the already installed `/usr/lib/jvm/java-25-openjdk` through `JAVA_HOME` for this process only.
- Started `./mvnw -pl services/yurlib-server spring-boot:run -Dspring-boot.run.profiles=loopback-dev` with database URL `jdbc:postgresql://127.0.0.1:5433/yurlib`, `SERVER_PORT=8082`, and absolute paths for the existing `main` and `managed` mounts.
- Backend startup validated all 12 migrations and applied pending migrations 11 and 12 to the existing local Yurlib database.
- Started `npm --prefix web/yurlib-web start -- --host 127.0.0.1 --port 4200 --proxy-config "$PWD/.local/proxy.test-startup.json"`.
- Runtime output is in ignored `.local/backend-test-startup.log` and `.local/frontend-test-startup.log`; both application processes remain running for manual testing.
- The optional document worker was not started; worker-dependent document metadata, covers, and conversion processing are not part of this startup.

## Results and verification

- PostgreSQL container is healthy and bound to `127.0.0.1:5433`.
- Frontend `http://127.0.0.1:4200/` returns HTTP 200.
- Health through the frontend proxy returns HTTP 200 and `UP`.
- Session through the frontend proxy returns HTTP 200, authenticated loopback development access, without a login requirement.
- Library-root endpoint returns HTTP 200 with one existing `AVAILABLE` read-only source root on mount `main`.
- Catalog endpoint returns HTTP 200 with nine existing Works.
- Startup compilation passed on Java 25; no source code was changed. Full automated suites were not run for this runtime-only request.

## Blockers

- None. Default port conflicts and the Java version mismatch were resolved with local runtime configuration.
