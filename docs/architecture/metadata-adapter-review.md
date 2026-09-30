# Metadata Adapter Dependency and Safety Review

- Status: Accepted M2 implementation baseline
- Scope: Plain EPUB, FB2, and MOBI metadata extraction
- Related issues: [#16](https://github.com/yurtools/yurlib/issues/16), [#49](https://github.com/yurtools/yurlib/issues/49)
- Reviewed: 2026-09-30

## Decision

The initial adapters use only Java runtime APIs. No third-party parser dependency is added.

| Format | Runtime APIs                                  | Parsed surface                                                        |
| ------ | --------------------------------------------- | --------------------------------------------------------------------- |
| EPUB   | `java.util.zip`, NIO, secure bounded JAXP DOM | EPUB mimetype, container package reference, and Dublin Core metadata  |
| FB2    | NIO and secure StAX                           | `description`, title information, contributors, document ID, and ISBN |
| MOBI   | bounded positional NIO reads                  | Palm database/MOBI headers, title, and selected EXTH metadata         |

This choice adds no license to the project's GPL-3.0-only dependency set. It also avoids general-purpose archive extraction and keeps each adapter replaceable behind `MetadataExtractor`.

## Format-neutral resource budget

Every extraction receives one monotonic budget. A failure names only the consumed resource and configured bound, for example `bytes-read` or `selected-value`; it never includes a physical path or metadata value. `PARSE_LIMIT_EXCEEDED` remains the stable per-file outcome.

| Resource                |                                                    M2 default | Classification                                  | Enforcement                                                                                              |
| ----------------------- | ------------------------------------------------------------: | ----------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Source facts            |                                           4 GiB admitted size | Hard security boundary                          | Pre-parse no-follow file facts; no buffer scales with this value                                         |
| Bytes read              |                                         64 MiB per extraction | Deployment-tunable within this hard ceiling     | Counting streams and positional reads share one counter                                                  |
| XML metadata            |                   4 MiB per selected document/metadata prefix | Hard security boundary                          | EPUB reads only container/OPF; FB2 stops at the end of `description`                                     |
| Archive directory       |                                      10,000 entries and 4 MiB | Hard security boundary                          | Bounded central-directory inspection; no filesystem extraction                                           |
| Selected scalar         |                                                  64 KiB UTF-8 | Hard security boundary                          | Checked incrementally before normalization or decoding                                                   |
| Structural depth        |                                                    128 levels | Hard security boundary                          | Checked for streaming XML events                                                                         |
| Open files/streams      | 4 per extraction; 32 per server in the staged-ingestion slice | Deployment-tunable permit with a hard ceiling   | Scoped leases; later composed with the server semaphore in #56                                           |
| Metadata elapsed time   |                                                    30 seconds | Deployment-tunable deadline with a hard ceiling | Monotonic cooperative checks during reads and structural traversal                                       |
| Encoded selected image  |                                                        32 MiB | Hard security boundary                          | No image is selected or decoded in metadata extraction; used by #57                                      |
| Decoded selected image  |                                     40 megapixels and 128 MiB | Hard security boundary                          | Header probe before allocation in #57                                                                    |
| Task memory reservation |               64 MiB metadata; 128 MiB archive-heavy metadata | Admission-control safeguard                     | Composed with the 512 MiB server pool in #56; current adapters expose largest controlled buffer evidence |

Raising a hard boundary requires threat review plus near-limit and over-limit fixtures. Deployment tuning can lower limits and can raise tunable defaults only up to the documented ceiling. The implementation does not claim that the Java runtime's internal parser objects are exactly equal to the task reservation; deterministic evidence therefore reports both counted input and largest application-controlled buffer, while reference runs report observed peak heap separately.

### EPUB

All entry names and the bounded central directory are validated without extraction. Encryption is rejected. Only `mimetype`, `META-INF/container.xml`, and the selected OPF package are inflated. The 4 MiB selected-entry expansion bound and 100:1 compression-ratio bound apply to those operation-relevant entries; a large unparsed image or content entry is not falsely rejected by the XML limit. ZIP64 structures outside the directory and source bounds are rejected.

### FB2

Secure StAX processing rejects DTDs, external entities, external resolution, and over-depth structures. It retains only approved scalar fields under `description` and returns at the closing `description` element. Body text and base64 `binary` payloads are never materialized or traversed for metadata extraction.

### MOBI

The adapter validates at most 4,096 monotonic record offsets, a 1 MiB record-zero range, a 1 MiB EXTH structure, and 1,024 EXTH records. It reads the Palm database header, record directory, required MOBI fields, EXTH record headers, selected EXTH values, and optional full-name range with positional reads. Unselected records and payloads are not loaded. Supported encoding and encryption checks happen before selected text is decoded.

## Outcomes and provenance

Successful results preserve observed title, contributors, language, identifiers, format, byte size, modification time, parser name, and parser version. Missing values remain absent.

The application boundary distinguishes:

- `FILE_UNSTABLE` as deferred when pre/post file facts differ;
- `UNSUPPORTED_FORMAT`;
- `ENCRYPTED_ASSET`;
- `CORRUPT_ASSET`;
- `PARSE_LIMIT_EXCEEDED`.

Plain `.fb2` is supported. `.fb2.zip` and other general archive ingestion remain excluded.

## Alternatives and consequences

- Relaxing the M1 1 MiB EPUB per-entry limit was rejected because it would preserve whole-archive reasoning and could replace a false rejection with unbounded work. Limits now follow the data actually opened.
- Whole-file FB2 buffering and DOM construction were replaced with streaming metadata selection. The trade-off is a small format-specific state machine, covered by depth, scalar, malformed XML, and large-binary fixtures.
- Reading complete MOBI record zero was replaced with explicit positional ranges. The trade-off is more offset validation, but read/allocation evidence is deterministic and large trailing records do not affect heap use.
- New third-party parsers remain deferred. Java runtime APIs satisfy the current metadata surface without a new license, CVE, or native isolation obligation; PDF, DOCX, and DjVu dependency choices remain gated by #51.

Parser provenance is now `jdk-epub` version `2`, `jdk-fb2-stax` version `2`, and `jdk-mobi-seek` version `3`. The aggregate extraction version is `bounded-metadata-v3`, so the next scan reprocesses older Assets through the existing append-only observation-set path. Unchanged files are then skipped idempotently at version 3, and active curated overrides remain separate.
