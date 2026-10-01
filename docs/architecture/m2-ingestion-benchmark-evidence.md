# M2 Ingestion Benchmark Evidence

- Status: M2 evidence complete; local and SMB observations recorded
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

To include the M2 mounted-storage observation, set the SMB path before the same command:

```bash
export YURLIB_BENCHMARK_SMB=/mounted/reference-smb-corpus
```

`YURLIB_BENCHMARK_LOCAL` may override the generated local corpus. Each supplied root is read recursively, capped at 1,000 supported files, and must contain no private content that cannot be used for local testing.

The harness also accepts `YURLIB_BENCHMARK_NFS` for deferred post-M2 issue [#70](https://github.com/yurtools/yurlib/issues/70). NFS evidence is not an M2 acceptance requirement.

## Observations

| Storage   | Files | Extracted | Deferred | Failed | Time to first result | Warm throughput |   Counted bytes |         Peak heap | Peak open files/task |       DB p95 | Interactive API p95 | Status   |
| --------- | ----: | --------: | -------: | -----: | -------------------: | --------------: | --------------: | ----------------: | -------------------: | -----------: | ------------------: | -------- |
| Local SSD |     6 |         6 |        0 |      0 |            36.813 ms |  414.45 files/s |     4,139 bytes | 121,732,552 bytes |                    2 | 2,531.234 µs |       15,140.425 µs | Observed |
| SMB       |   136 |        26 |        0 |    110 |             9.098 ms |  243.07 files/s | 3,641,034 bytes | 271,154,632 bytes |                    2 | 3,643.164 µs |       14,331.904 µs | Observed |

The SMB observation used an owner-provided corpus on a local-network CIFS 3.0 mount with strict caching, 4-MiB read/write sizes, and a one-second attribute-cache timeout. The 136 supported files comprised 87 DjVu, 46 EPUB, two DOCX, and one MOBI files. Aggregate outcomes were:

- DjVu: 0 extracted, 87 safely failed.
- EPUB: 23 extracted, 23 safely failed.
- DOCX: 2 extracted, 0 failed.
- MOBI: 1 extracted, 0 failed.
- Failure codes: 67 `UNSUPPORTED_FORMAT` and 43 `CORRUPT_ASSET`.

Aggregate-only follow-up diagnosis found that 90 failures are confirmed parser compatibility gaps rather than unsafe inputs: 67 DjVu files contain valid nested `FORM:DJVI` shared-information components, and 23 EPUB files contain ordinary explicit ZIP directory entries whose trailing slash is lost by host-path normalization. The remaining 20 DjVu outcomes comprise 19 padding-boundary failures and one top-level length failure; issue [#69](https://github.com/yurtools/yurlib/issues/69) requires comparison with a reference implementation before relaxing or retaining those rejections. These fixes and that validation are M2 scope. All byte, archive, nesting, path-traversal, deadline, and memory limits remain mandatory.

No filenames, metadata values, or book content were recorded.

## Interpretation limits

These values are local observations, not release guarantees. The generated local corpus is small and emphasizes deterministic pipeline overhead. Real corpora can contain malformed or unsupported-in-practice files, so the harness reports extracted, deferred, and safely failed outcomes instead of requiring every file to parse. SMB results are only valid when the mounted path, server, network, cache state, and corpus are described. “Cold” means the first application-level extraction in the run; the harness does not claim to flush kernel, NAS, or device caches.
