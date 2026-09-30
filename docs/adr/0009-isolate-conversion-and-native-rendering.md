# ADR-0009: Isolate Conversion and Native Rendering

- Status: Accepted
- Date: 2026-09-30
- Decider: Yurlib project owner

## Context

Converters and native document renderers process hostile files and may have memory-safety, subprocess, codec, and resource-exhaustion risks. Running them inside `yurlib-server` would share credentials, source mounts, memory, and failure fate.

## Decision

Use one optional non-root worker container for approved conversion and native rendering operations. It has no database credentials, source-root mounts, shell interpolation, or general egress. It communicates only with an authenticated internal server API, reads per-job staged input, writes quota-bound unpublished output, and runs with CPU, memory, PID, time, and filesystem limits.

The server owns PostgreSQL jobs, copies and hashes staged input, validates output, and atomically publishes it. Initial conversion routes are FB2 → EPUB and MOBI → EPUB. Tool artifacts and versions require dependency/license review and pinned reproducible packaging.

## Consequences

### Positive

- Hostile tool failures are isolated from the catalog process and database.
- Queue durability remains in PostgreSQL without giving another deployable database ownership.
- Routes and render operations are explicit, versioned contracts.

### Negative

- Deployment gains one optional container and authenticated internal protocol.
- Staging copies increase temporary I/O and storage.

## Alternatives considered

### Spawn tools from `yurlib-server`

Rejected because failure and privilege isolation are materially weaker.

### Separate worker with direct database access

Rejected because it violates the backend database-ownership boundary and broadens credentials.

## Supersession

This is an approved security-boundary exception under ADR-0002's service-extraction criteria; the catalog remains a modular monolith.
