# ADR-0003: Bound the First Local-Library Slice

- Status: Accepted
- Date: 2026-09-29
- Decider: Yurlib project owner

## Context

Yurlib has a deployable Spring Boot modular monolith, Angular shell, PostgreSQL development database, and deterministic CI, but it does not yet have an end-to-end product workflow. The first workflow must validate local filesystem access, durable background work, catalog persistence, the browser/API boundary, and source-file safety without introducing infrastructure needed only by later milestones.

The product concept requires read-only library roots, EPUB/FB2/MOBI discovery, persistent progress, idempotent rescans, restart recovery, unavailable-root safety, and original-asset downloads. It also requires authentication for shared-network deployment, but permits a deliberately configured loopback-only development mode.

## Decision

The first local-library slice will use these boundaries:

- one configured `READ_ONLY` library root per initial walking-skeleton deployment;
- deployment-owned mount aliases map to canonical allowed filesystem prefixes; API clients select an alias and relative path rather than submitting an unrestricted host path;
- a required operator-created identity marker verifies that the expected source is mounted before a scan can reconcile missing locations;
- plain EPUB, FB2, and MOBI files are in scope; `.fb2.zip` and other archives are deferred;
- PostgreSQL stores root configuration, scan jobs, per-file outcomes, catalog records, metadata observations, and progress;
- one bounded in-process worker executes durable jobs; no message broker is introduced;
- clients poll job resources for progress; no WebSocket or server-sent-event infrastructure is introduced;
- a restart may repeat filesystem discovery, but unchanged assets are not reparsed and catalog effects remain idempotent;
- the first walking skeleton may run without login only in an explicit loopback-only development mode;
- authenticated owner access is required before an M1 deployment can listen on a non-loopback interface;
- downloads use an asset identifier, revalidate availability and path containment, and stream the original file without accepting a client-supplied filesystem path.

### Justification

- The design validates the riskiest local-filesystem and persistence boundaries end to end.
- It follows ADR-0002 by keeping asynchronous work durable without adding a broker.
- Mount aliases and identity markers make accidental access outside approved roots and wrong/unavailable mounts detectable.
- Polling and an in-process worker minimize operational complexity while leaving explicit interfaces for later evolution.
- Deferring archives avoids introducing archive-bomb handling before the plain-format path is proven.

## Consequences

### Positive

- The first increment remains deployable with the existing Spring Boot, Angular, PostgreSQL, and Compose baseline.
- Source files remain read-only and are never reorganized by this slice.
- Scan progress and recovery survive application restarts.
- Missing-file reconciliation cannot run after an incomplete traversal or failed identity check.
- The API does not expose arbitrary server filesystem access.

### Negative

- Operators must configure an allowed mount alias and identity marker before scanning.
- Polling creates some repeated HTTP traffic.
- Only one active scan per root is supported initially.
- Discovery may restart at the root after a process restart, although unchanged payloads are skipped.
- Archive-packaged FB2 collections require a later decision and security design.

## Alternatives considered

### Synchronous scan request

Rejected because large or unavailable roots would hold HTTP requests open and provide poor recovery or progress behavior.

### Message broker and separate ingestion service

Rejected because current throughput, isolation, and team constraints do not justify distributed infrastructure. ADR-0002 already defines PostgreSQL-backed durable jobs as the default.

### Unrestricted absolute paths in the API

Rejected because they expose host layout, increase path-traversal risk, and let a compromised client probe files outside administrator-approved mounts.

### Filesystem-only configuration

Rejected because roots need durable identity, availability, scan history, and API-visible state. Deployment configuration still owns the allowed physical mount mappings.

### Include `.fb2.zip` immediately

Rejected for the first slice because archive expansion and nested-content limits add a distinct security surface. It remains an explicit M1 follow-up decision.

### WebSocket progress

Rejected because polling satisfies the first workflow without new connection lifecycle and deployment concerns.

## Supersession

None.
