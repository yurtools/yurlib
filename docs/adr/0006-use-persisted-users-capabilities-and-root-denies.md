# ADR-0006: Use Persisted Users, Capabilities and Root Denies

- Status: Accepted
- Date: 2026-09-30
- Decider: Yurlib project owner

## Context

M2 adds personal state, multiple roots, source administration, and shared curation. The M1 deployment-defined owner identity cannot own per-user data or express least-privilege access.

## Decision

Bootstrap a persisted owner on first shared-network M2 startup, then authenticate all shared-network users against PostgreSQL-backed credentials and server sessions. Only the owner administers accounts, capabilities, credentials, and root denies.

Use independent `MANAGE_INGESTION_SOURCES` and `CURATE_CATALOG` capabilities; the owner has both by default. Root access is allow-by-default with explicit per-user denies. A Work is visible when at least one Asset is on an allowed root. Denied Assets, observations, provenance, jobs, covers, and downloads are hidden. Derived Assets require access to their source lineage and output root. Authority reductions invalidate sessions immediately.

Shared tags are curator-managed; collections are private per user. Read state belongs to Work and may retain the completed Edition as evidence.

## Consequences

### Positive

- Source management, catalog curation, and reading access are independent.
- Personal state has a durable owner and root denies are testable end to end.
- Default access remains simple for trusted self-hosted deployments.

### Negative

- Allow-by-default requires careful handling when adding a sensitive root.
- Every catalog query and derived operation needs an authorization projection.
- Account bootstrap, recovery, session invalidation, and privacy workflows enter M2.

## Alternatives considered

### One persisted owner only

Rejected because it cannot provide the requested per-user state or root visibility.

### Deny by default

Rejected because the owner explicitly selected default visibility with targeted denies.

### Conflate source manager and curator

Rejected because filesystem administration and shared bibliographic authority are different privileges.

## Supersession

Supersedes ADR-0004's single deployment-owner model for M2 shared-network operation. ADR-0004 remains the M1 record and its session, CSRF, TLS, and loopback safety requirements continue.
