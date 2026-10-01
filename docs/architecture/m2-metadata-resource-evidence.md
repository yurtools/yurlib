# M2 Metadata Resource Evidence

- Status: Observed implementation evidence
- Date: 2026-09-30
- Issue: [#49](https://github.com/yurtools/yurlib/issues/49)
- Extraction version: `bounded-metadata-v3`

## Method

`MetadataReferenceBenchmarkTest` performs one warm-up extraction followed by 12 sequential measured extractions per generated fixture. The run used JDK 26 with four active processors, an 8 GiB JVM-visible RAM ceiling, and a 512 MiB maximum heap:

```bash
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -XX:MaxRAM=8g -Xmx512m' \
  taskset -c 0-3 ./mvnw -B -pl services/yurlib-server \
  -Djacoco.skip=true -Dtest=MetadataReferenceBenchmarkTest test
```

The generated files contain no private book content. The EPUB contains a 2 MiB expanded but unparsed content entry, the FB2 contains a 96 MiB embedded binary body after its metadata description, and the MOBI is a 256 MiB sparse file whose selected metadata remains in bounded record zero.

## Observation

| Fixture                    |     Source bytes | Average counted bytes read | Largest controlled buffer | Observed process peak heap | Warm files/second |
| -------------------------- | ---------------: | -------------------------: | ------------------------: | -------------------------: | ----------------: |
| EPUB large unparsed entry  | 3,300 compressed |                      4,407 |                 4,194,305 |                 50,123,552 |            219.15 |
| FB2 96 MiB embedded binary |      100,663,721 |                      8,230 |                         0 |                 54,317,856 |          1,012.57 |
| MOBI 256 MiB sparse source |      268,435,456 |                        364 |                       132 |                 55,366,432 |          2,391.42 |

Counted bytes and controlled buffers are deterministic adapter evidence. Peak heap is the absolute process-wide sum of heap-pool peak usage after resetting pool peaks; it includes the JVM, JUnit, fixture state, and adapter allocations and is not attributed solely to a parser. Throughput is a local warm observation, not a release claim or universal target. Local SSD and SMB concurrency and interactive-latency evidence are recorded by #56. NFS benchmark evidence is deferred beyond M2 in #70.

## Acceptance interpretation

- Source size does not determine bytes read or controlled buffer size for the large FB2 and MOBI fixtures.
- The EPUB adapter validates its bounded directory and inflates only operation-relevant metadata entries; the large unrelated entry is not charged to the XML limit.
- Architecture tests reject whole-file read APIs in production metadata adapters.
- Near-limit and over-limit fixtures separately exercise EPUB selected expansion, FB2 metadata-prefix and selected-value bounds, MOBI selected values, record counts, malformed inputs, and encryption outcomes.
