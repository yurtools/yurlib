# ADR-0011: Isolate PDF Metadata and Parse DOCX/DjVu Structures In Process

- Status: Accepted
- Date: 2026-09-30
- Decider: Yurlib project owner

## Context

M2 adds PDF, DOCX, and DjVu catalog support. All three formats are hostile inputs, but their metadata surfaces and parser risk differ.

PDF requires complex xref, object-stream, encryption, and decompression handling. Apache PDFBox 3.0.8 is the current fixed 3.0 release, is Apache-2.0 licensed, and supports caller-provided random access. Its published security model nevertheless excludes denial of service through excessive CPU, memory, recursion, or processing time and recommends timeouts, resource controls, and sandboxing for untrusted documents at scale.

DOCX metadata is contained in a ZIP/OPC package whose bounded directory, selected XML parts, relationships, and content types can reuse Yurlib's existing Java ZIP/XML controls. DjVu catalog facts are available from its IFF-style container and bounded structural chunks; image decoding and compressed annotation processing are not required for catalog admission.

The existing isolated worker in ADR-0009 is already the approved boundary for hostile native rendering and conversion. This decision determines whether PDF metadata joins that boundary and whether new DOCX or DjVu parser dependencies are justified.

## Decision

Run Apache PDFBox 3.0.8 metadata extraction in the ADR-0009 isolated worker. Pin the dependency and its transitive artifacts, retain their license notices, and exclude optional image codecs, command-line tools, embedded-file examples, Preflight, and Bouncy Castle. The operation receives one staged read-only PDF and emits a bounded metadata result; it has no source-root mount, database credential, shell interpolation, or network egress.

The server persists and owns PDF metadata jobs. Worker execution is constrained by CPU, memory, PID, wall-time, read, allocation, and temporary-storage limits. PDFBox receives a budgeted random-access source and bounded scratch cache. It may inspect document information, selected XMP, encryption state, and page count only. It must not render pages, extract content, resolve network references, execute actions or JavaScript, activate forms/media, or materialize embedded files. A PDF that opens only with a password, or reports encryption even with an empty password, yields `ENCRYPTED_ASSET` without metadata disclosure.

Parse DOCX metadata in `yurlib-server` with Java ZIP and secure XML APIs. Validate OPC content types, entry paths, central-directory bounds, compression ratios, selected expanded bytes, and internal relationships. Read only core properties, custom properties, and the minimum package relationship/content-type parts required to validate them. Reject external relationships and macro/OLE/embedded-package activation; never extract package members beside the source.

Parse DjVu catalog metadata in `yurlib-server` with a bounded positional IFF parser. Validate `AT&TFORM`, `DJVU`/`DJVM`, chunk lengths, padding, nesting, counts, and selected scalar values. Extract page count/facts plus bounded uncompressed metadata annotations when present. Do not decode image chunks or compressed `ANTz`/text payloads in the server. DjVuLibre remains confined to the isolated worker for later approved page rendering; it is not a catalog-parser dependency.

Detection is content based after extension discovery: PDF requires a valid PDF header within the allowed prefix, DOCX requires a valid OPC package with the Word main-document content type, and DjVu requires the DjVu IFF signature and supported form type. The aggregate extraction version and each parser/worker version are provenance and reprocessing inputs.

## Consequences

### Positive

- PDF parser denial of service and unexpected runtime faults do not share the server's memory, credentials, source mounts, or failure fate.
- DOCX and DjVu catalog admission add no broad office suite or native library to the server.
- The existing cross-format resource budget remains authoritative and evidence can distinguish server reads from worker CPU, memory, and temporary storage.
- Apache-2.0 PDFBox is compatible with Yurlib's GPL-3.0-only distribution, with attribution preserved.

### Negative

- Complete PDF metadata support requires the isolated worker; without it, discovered PDF metadata work remains visibly pending rather than being parsed unsafely in the server.
- The worker protocol and packaging arrive earlier than conversion and page rendering.
- Server-side DjVu metadata intentionally omits compressed annotations until a separately bounded worker operation is approved.
- DOCX structural parsing requires format-specific OPC validation code instead of a general office-document library.

## Alternatives considered

### Run PDFBox in `yurlib-server`

Rejected because a counting random-access wrapper does not bound PDFBox's internal CPU, recursion, decompression, or allocation behavior, and Java cannot safely terminate a stuck parser thread. This conflicts with the accepted hard resource and failure-isolation requirements.

### Implement a custom PDF parser

Rejected because correct xref tables/streams, object streams, incremental updates, encryption, filters, and XMP traversal would create a large security-sensitive parser surface with weaker compatibility and maintenance than PDFBox.

### Use Apache POI for DOCX metadata

Rejected for this bounded catalog surface because it adds a broad dependency graph and general document model when only a few validated OPC parts are required. Reconsider only if later approved DOCX features cannot be implemented safely with selected-part parsing.

### Use DjVuLibre in the server

Rejected because it is native GPL code processing hostile compressed content. Existing ADR-0009 requires native document tooling to run in the isolated worker, and catalog facts do not require image decoding.

## Dependency and maintenance review

- PDFBox 3.0.8 is the current Apache 3.0 release and fixes the published 3.0.7 examples-module path-traversal issue. Yurlib does not include or copy the affected embedded-file extraction example.
- Include only the PDFBox core artifacts required by the worker. Optional JBIG2/JPEG2000/TIFF codecs are unnecessary for metadata and remain excluded.
- Renovation or manual upgrades must review the Apache security page, release notes, dependency tree, licenses, worker fixtures, and resource evidence before changing the pinned version.
- Official references: [PDFBox security](https://pdfbox.apache.org/security.html), [PDFBox 3.0 migration and random access](https://pdfbox.apache.org/3.0/migration.html), [PDFBox dependencies](https://pdfbox.apache.org/3.0/dependencies.html), and [GNU GPLv3 compatibility guidance](https://www.gnu.org/licenses/license-compatibility.html).

## Supersession

Extends ADR-0009 for PDF metadata parsing. It does not supersede ADR-0009 or ADR-0010.
