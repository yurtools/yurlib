# ADR-0008: Use Typed Managed-Output Roots and Lineage Authorization

- Status: Accepted
- Date: 2026-09-30
- Decider: Yurlib project owner

## Context

M1 supports one read-only source. M2 needs multiple sources plus writable covers and conversions without writing beside originals or bypassing root denies.

## Decision

Support multiple roots, each with exactly one mode: `READ_ONLY_SOURCE` or `MANAGED_OUTPUT`. Deployment aliases and identity markers retain the containment boundary. Managed files use opaque paths and atomic same-filesystem publication. Cover and conversion purposes each select one default managed root.

Every derived Asset records its complete source lineage. A user may access it only when the output root and every security-relevant source root are allowed. Backup covers PostgreSQL and managed roots; read-only source bytes remain external.

## Consequences

### Positive

- Originals remain immutable and generated files have explicit ownership.
- Multiple roots and derived outputs share one authorization rule.
- Backup/restore can distinguish managed state from external sources.

### Negative

- Deployments must provision writable managed storage.
- Moving a managed root needs a controlled migration rather than path editing.

## Alternatives considered

### Write derivatives beside source files

Rejected because source roots are read-only and user-controlled filenames are unsafe.

### Authorize only by output root

Rejected because conversion could launder content from a denied source into a visible root.

## Supersession

Supersedes ADR-0003's single-root restriction for M2 while preserving its mount-alias, identity, and containment rules.
