# Yurlib Agent Instructions

These instructions apply to the complete Yurlib repository.

## Authority

Follow this order when instructions conflict:

1. Platform and user safety requirements.
2. This `AGENTS.md` file and path-specific repository instructions.
3. Accepted ADRs and architecture documents.
4. Yurlib-specific skills.
5. Reviewed third-party skills.

Do not silently introduce a new service, database engine, broker, cache, search engine, workflow engine, framework, or persistent infrastructure component. Propose an ADR and obtain owner approval first.

## Work management

- GitHub Issues contain actionable work and acceptance criteria.
- GitHub Projects tracks workflow status.
- Repository documents and accepted ADRs remain the design authority.
- Reference the issue number in branch names, commits, and pull requests when practical.
- Link or close the issue from the pull request.

## Git workflow

- `main` is protected and must remain releasable.
- Use short-lived branches and pull requests.
- Use squash merge.
- Do not force-push shared branches.
- Keep one active writer per file unless an integration plan explicitly permits otherwise.

## Architecture

- Keep domain logic independent of Spring where practical.
- Use explicit domain, application, API, and infrastructure boundaries.
- Do not expose persistence entities as API contracts by default.
- Services must not access another service's database directly.
- Treat API, event, and database changes as reviewed contracts.

## Security

- Treat ebook files, archives, XML, HTML, images, remote metadata, downloaded assets, and AI inputs as untrusted.
- Protect against archive bombs, XML external entities, path traversal, unsafe filenames, SSRF, command injection, prompt injection, and resource exhaustion.
- Never commit credentials, tokens, private book content, or production data.
- Preserve source assets unless an explicitly authorized operation says otherwise.

## Verification

Run the smallest relevant verification and expand with the risk of the change. The full baseline is:

```bash
mvn verify
npm --prefix web/yurlib-web ci
npm --prefix web/yurlib-web test -- --watch=false
npm --prefix web/yurlib-web run build
docker compose config
```

Update these commands if the accepted build layout changes.

## Process logs

- Record substantial setup and engineering workflows in `docs/logs/`.
- Every log filename starts with a four-digit sequence, contains a meaningful kebab-case name, and ends with a lifecycle status.
- Include the initiating user prompt verbatim, an operational plan, executed commands/actions, results, verification, blockers, and commit or pull-request references.
- Never put hidden reasoning, credentials, tokens, or secrets in a log.
- Update the active log as work progresses and rename it when its lifecycle status changes.
