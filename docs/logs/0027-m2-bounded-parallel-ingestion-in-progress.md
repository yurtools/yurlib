# M2 Bounded Parallel Ingestion

- Status: In progress
- Started: 2026-10-01
- Branch: `feat/56-bounded-parallel-ingestion`
- Issue: [#56](https://github.com/yurtools/yurlib/issues/56)
- Repository: `yurtools/yurlib`

## Prompt

> continue

## Plan

1. Reconcile merged pull request #67 and start issue #56 from merged `main`.
2. Replace whole-scan single-worker execution with PostgreSQL-backed staged tasks using transactional claims, leases, heartbeats, bounded attempts, and idempotency keys.
3. Enforce bounded discovery queues, parse concurrency, weighted memory, open-file, CPU, and database permits.
4. Provide fair progress across roots, cooperative cancellation, lease-expiry recovery, and deterministic single-versus-parallel outcomes.
5. Add operational metrics and a reference benchmark harness/report for local SSD, SMB, and NFS observations without making universal performance claims.
6. Verify migrations, concurrency and failure recovery, backend/frontend baselines, Compose configuration, and source immutability; then open a pull request that closes #56.

## Actions and results

- Confirmed pull request #67 was squash-merged as `17763ecf5743fd6b8ae1c9b23eddfa0cc30a60db`; all four CI jobs passed, issue #55 is closed, and its Project item is Done.
- Fast-forwarded local `main` and created `feat/56-bounded-parallel-ingestion`, preserving the unrelated local Angular analytics preference.
- Confirmed issue #56 is next in the accepted M2 implementation order and that its prerequisites are merged.
- Reviewed ADR-0007, M2 design section 8, existing parser resource budgets, scan-job persistence, discovery flow, scheduling, telemetry, and tests.
- Added Flyway migration V8 for durable ingestion tasks, per-root scheduling state, discovery completion, cancellation requests, task idempotency keys, bounded attempts, and lease/heartbeat state.
- Refactored scan execution into a bounded discovery producer and metadata consumers. The scheduler permits one discovery worker and one to four metadata workers, with two metadata workers by default.
- Added transactional PostgreSQL task claims with `FOR UPDATE SKIP LOCKED`, root-fair selection, expiring leases, stale-lease rejection, three-attempt retry exhaustion, and deterministic enqueue idempotency.
- Added hard resource ceilings for weighted parser memory, open files, CPU work, and database work. Default ceilings are 512 MiB, 32 files, two CPU permits, and two database permits.
- Revalidated each contained path immediately before parsing and retained parser deadlines and byte budgets for untrusted files.
- Added cooperative discovery cancellation, queued-task cancellation, a secured scan-job cancellation endpoint, and OpenAPI coverage.
- Added queue, running-task, memory-permit, and open-file-permit telemetry.
- Added integration coverage for capacity, idempotency, root fairness, lease recovery, stale-worker rejection, retry exhaustion, and cancellation. Extended the walking-skeleton acceptance test to compare sequential and parallel catalog observations.
- Added an opt-in reference benchmark and `docs/architecture/m2-ingestion-benchmark-evidence.md`. Recorded local-SSD evidence under the documented 4-core, 8-GiB, 512-MiB-heap profile; the harness accepts owner-provided local, SMB, and NFS corpus paths.
- Corrected the PMD row-mapper finding and the SpotBugs constructor/finalizer warning without suppressing either rule.
- Updated issue #56 to mark the five verified implementation criteria complete and left the SMB/NFS benchmark criterion open with a comment linking the local evidence and verification results.
- Committed the implementation as `76a2f6a` (`feat(#56): add bounded parallel ingestion`).

## Verification

- `YURLIB_RUN_INGESTION_BENCHMARK=true MAVEN_OPTS='-Xms256m -Xmx512m -XX:ActiveProcessorCount=4' ./mvnw -pl services/yurlib-server -Dtest=IngestionReferenceBenchmarkTest test`: passed for the generated local corpus.
  - Time to first result: 32.724 ms.
  - Warm throughput: 645.43 files/s.
  - Counted bytes: 4,139.
  - Peak heap: 138,930,424 bytes.
  - Peak open files per task: 2.
  - Database p95: 2,606.808 microseconds.
  - Interactive health API p95 under extraction load: 8,301.418 microseconds.
- `./mvnw verify`: passed for the complete reactor. The server ran 139 tests with one opt-in benchmark skipped; the isolated document worker ran three tests. Coverage, formatting, OpenAPI compatibility, PMD, and SpotBugs passed with zero findings. Earlier verification runs exposed one PMD finding and one SpotBugs constructor warning; both were corrected without rule suppression.
- `npm --prefix web/yurlib-web ci`: passed; 267 packages installed, zero reported vulnerabilities. npm reported the existing blocked optional install scripts.
- `npm --prefix web/yurlib-web test -- --watch=false`: passed, 13 tests in two files.
- `npm --prefix web/yurlib-web run build`: passed.
- `docker compose config`: passed.
- `git diff --check`: passed.

## Blockers

- SMB and NFS benchmark measurements require owner-provided mounted reference paths. The harness and reporting format can be implemented and local evidence recorded independently.
