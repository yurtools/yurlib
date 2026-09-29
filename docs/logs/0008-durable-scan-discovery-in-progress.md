# Durable Scan Jobs and Bounded Discovery

- Status: In progress
- Started: 2026-09-29
- Branch: `feature/15-durable-scan-discovery`
- Issue: [#15](https://github.com/yurtools/yurlib/issues/15)
- Repository: `yurtools/yurlib`

## Prompt

> continue with development

## Plan

1. Reconcile the accepted scan lifecycle and recovery rules with the existing schema and API contract.
2. Add framework-independent scan use cases and filesystem discovery ports while preserving the domain/application boundaries.
3. Implement transactional queueing, database-visible claims, leases, heartbeats, durable counters, and expired-job recovery without a broker.
4. Walk only the verified configured root without following symlinks; emit normalized contained candidate paths and isolate per-file failures.
5. Gate missing-location reconciliation on verified identity and complete traversal.
6. Add API, persistence, concurrency, restart-recovery, containment, failure-isolation, and progress tests.
7. Run the full verification baseline, commit, push, open a pull request linked to issue #15, and pass protected-branch checks.

## Execution log

### 2026-09-29 — Preflight

- Completed prerequisite issue #23 through pull request #30 and synchronized `main` at `7d19e63`.
- Confirmed issue #15 is open, both dependencies are complete, and moved it to `In Progress` in Yurlib Engineering.
- Created `feature/15-durable-scan-discovery` from synchronized `main`.
- Continued using the project Spring Boot and testing guidance for this backend slice.

### 2026-09-29 — Design reconciliation

- Confirmed Flyway V2 already provides scan states, timestamps, heartbeat, durable counters, completion coverage, file outcomes, and the partial unique index for one active scan per root.
- Kept the design migration-free unless implementation tests prove another persisted field is required.
- Selected an atomic PostgreSQL claim using row locking, with expired `RUNNING` jobs returned to the claimable lifecycle from their persisted heartbeat.
- Kept the worker single-threaded and bounded for M1; no broker, scheduler service, or additional infrastructure is introduced.
- Defined discovery as a filesystem adapter behind an application port. Parser/candidate processing remains an explicit port for issue #16, and catalog missing-location reconciliation remains an explicit port for issue #24.
- Production discovery will not follow directory or file symlinks and will persist only normalized paths relative to the already verified root.
- Reconciliation will be callable only after identity verification and complete traversal; tests will prove that failed or partial discovery cannot invoke it.

## Verification

Pending implementation.

## Result

In progress.
