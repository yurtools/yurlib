# Metadata Adapter Dependency and Safety Review

- Status: Accepted implementation baseline
- Scope: M1 plain EPUB, FB2, and MOBI metadata extraction
- Related issue: [#16](https://github.com/yurtools/yurlib/issues/16)
- Reviewed: 2026-09-29

## Decision

The initial adapters use only Java runtime APIs. No third-party parser dependency is added.

| Format | Runtime APIs                          | Parsed surface                                                       |
| ------ | ------------------------------------- | -------------------------------------------------------------------- |
| EPUB   | `java.util.zip`, NIO, secure JAXP DOM | EPUB mimetype, container package reference, and Dublin Core metadata |
| FB2    | NIO and secure JAXP DOM               | `title-info`, authors, language, document ID, and ISBN               |
| MOBI   | bounded NIO channels and byte buffers | Palm database/MOBI headers, title, and selected EXTH metadata        |

This choice adds no license to the project's GPL-3.0-only dependency set. It also avoids general-purpose archive extraction and keeps each adapter replaceable behind `MetadataExtractor`.

## Resource and input limits

| Area                   | Initial limit or rule                              |
| ---------------------- | -------------------------------------------------- |
| EPUB source            | 256 MiB                                            |
| EPUB entries           | 10,000                                             |
| EPUB central directory | 4 MiB                                              |
| EPUB entry expansion   | 1 MiB per entry, 64 MiB total, ratio at most 100:1 |
| EPUB XML metadata      | 1 MiB per parsed document                          |
| FB2 source/XML         | 8 MiB                                              |
| MOBI source            | 256 MiB                                            |
| MOBI records           | 4,096                                              |
| MOBI record zero       | 1 MiB                                              |
| MOBI EXTH records      | 1,024 records and 64 KiB per selected value        |

EPUB entries are inspected in place and are never extracted to the filesystem. Absolute, backslash, dot, and parent-traversal entry names are rejected. ZIP64 structures outside these limits are rejected.

JAXP processing enables secure processing, rejects document type declarations, disables external general and parameter entities, disables XInclude and entity expansion, and forbids external DTD/schema access.

MOBI parsing validates record counts, monotonic contained offsets, bounded header lengths, supported encodings, encryption state, and bounded EXTH record lengths before allocation or decoding.

## Outcomes and provenance

Successful results preserve observed title, contributors, language, identifiers, format, byte size, modification time, parser name, and parser version. Missing values remain absent.

The application boundary distinguishes:

- `FILE_UNSTABLE` as deferred when pre/post file facts differ;
- `UNSUPPORTED_FORMAT`;
- `ENCRYPTED_ASSET`;
- `CORRUPT_ASSET`;
- `PARSE_LIMIT_EXCEEDED`.

Plain `.fb2` is supported. `.fb2.zip` and other general archive ingestion remain excluded.
