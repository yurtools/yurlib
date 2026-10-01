# M2 Ingestion Benchmark Evidence

- Status: Local observation recorded; SMB and NFS observations pending mounted reference paths
- Date: 2026-10-01
- Issue: [#56](https://github.com/yurtools/yurlib/issues/56)
- Pipeline: PostgreSQL-backed staged ingestion, schema version 8

## Method

`IngestionReferenceBenchmarkTest` measures a first-result observation followed by a two-worker warm pass. It reports counted parser bytes, process peak heap, peak per-task open files, PostgreSQL `SELECT 1` p95, and health-API p95 while two metadata-extraction loops remain active. It uses generated fixtures by default for local storage and accepts existing mounted corpora through environment variables. It does not modify source files.

Run the four-core, 8-GiB reference profile with a 512-MiB heap:

```bash
YURLIB_RUN_INGESTION_BENCHMARK=true \
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -XX:MaxRAM=8g -Xmx512m' \
taskset -c 0-3 ./mvnw -B -pl services/yurlib-server \
  -Djacoco.skip=true -Dtest=IngestionReferenceBenchmarkTest test
```

To include mounted storage observations, set one or both paths before the same command:

```bash
export YURLIB_BENCHMARK_SMB=/mounted/reference-smb-corpus
export YURLIB_BENCHMARK_NFS=/mounted/reference-nfs-corpus
```

`YURLIB_BENCHMARK_LOCAL` may override the generated local corpus. Each supplied root is read recursively, capped at 1,000 supported files, and must contain no private content that cannot be used for local testing.

## Observations

| Storage   | Files | Time to first result | Warm throughput | Counted bytes |         Peak heap | Peak open files/task |       DB p95 | Interactive API p95 | Status                       |
| --------- | ----: | -------------------: | --------------: | ------------: | ----------------: | -------------------: | -----------: | ------------------: | ---------------------------- |
| Local SSD |     6 |            32.724 ms |  645.43 files/s |   4,139 bytes | 138,930,424 bytes |                    2 | 2,606.808 µs |        8,301.418 µs | Observed                     |
| SMB       |     — |                    — |               — |             — |                 — |                    — |            — |                   — | Reference mount not provided |
| NFS       |     — |                    — |               — |             — |                 — |                    — |            — |                   — | Reference mount not provided |

## Interpretation limits

These values are local observations, not release guarantees. The generated local corpus is small and emphasizes deterministic pipeline overhead. SMB and NFS results are only valid when the mounted path, server, network, cache state, and corpus are described. “Cold” means the first application-level extraction in the run; the harness does not claim to flush kernel, NAS, or device caches.
