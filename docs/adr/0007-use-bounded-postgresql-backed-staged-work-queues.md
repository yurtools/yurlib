# ADR-0007: Use Bounded PostgreSQL-Backed Staged Work Queues

- Status: Proposed
- Date: 2026-09-30
- Decider: Yurlib project owner

## Context

M2 needs parallel parsing, hashing, cover work, and conversion while keeping memory, open files, database load, NAS traffic, retries, and duplicate effects bounded. ADR-0002 rejects a broker without demonstrated need.

## Decision

Persist jobs and stage tasks in PostgreSQL. Workers claim with transactional skip-locked selection, leases, heartbeats, attempts, and idempotency keys. In-process ingestion uses bounded queues, weighted memory/open-file permits, cooperative cancellation, and per-root fairness. The external worker claims conversion/render work through an authenticated server API; it has no database credentials.

Default reference concurrency is one discovery producer, two metadata workers, one hasher, one persistence consumer, one render task, and one conversion task. Queue and resource ceilings are defined in the M2 design and verified under constrained memory.

## Consequences

### Positive

- Work survives restart without adding a broker.
- Backpressure and aggregate resource use are explicit.
- Claims and catalog publication remain transactionally coordinated by the server.

### Negative

- PostgreSQL receives queue traffic and requires careful indexing/cleanup.
- Lease expiry and idempotency behavior require failure-injection tests.

## Alternatives considered

### Unbounded executor queues

Rejected because configured concurrency would not bound memory or NAS pressure.

### Message broker

Rejected because the current single-instance reference deployment does not justify new persistent infrastructure.

## Supersession

Extends ADR-0002 and ADR-0003 from one worker to bounded staged concurrency.
