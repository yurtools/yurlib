# ADR-0005: Separate Observed, Normalized, Resolved and Curated Metadata

- Status: Proposed
- Date: 2026-09-30
- Decider: Yurlib project owner

## Context

M1 persists parser observations but does not reconcile inconsistent metadata or permit safe correction. Reprocessing and merges must improve display values without destroying source evidence or silently undoing owner choices.

## Decision

Store immutable observations with Asset/root/parser provenance. Produce versioned normalized facts and deterministic resolved values as derived state. Store curator overrides and merge/split events as separately audited, optimistic-concurrency-controlled state. Display precedence is curated, resolved, selected observation, then explicitly provisional fallback.

Works represent books or general documents; Editions represent language/publication/revision; Assets represent exact original or derived bytes. Full hashes may consolidate exact Assets, but metadata similarity only creates review candidates.

## Consequences

### Positive

- Reprocessing is reproducible and never destroys evidence or active overrides.
- Conflicts, corrections, undo, and provenance remain explainable.
- Parser behavior stays separate from catalog truth.

### Negative

- More tables, versions, and projection logic are required.
- Merge/split recovery needs explicit history and conflict handling.

## Alternatives considered

### Rewrite canonical columns during ingestion

Rejected because rescans could destroy evidence and owner corrections.

### Keep only raw observations and resolve in every query

Rejected because behavior, performance, review queues, and historical explanations would be unstable.

## Supersession

Extends ADR-0003; it does not change M1 historical acceptance.
