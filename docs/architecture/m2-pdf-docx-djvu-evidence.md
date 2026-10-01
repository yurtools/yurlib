# M2 PDF, DOCX, and DjVu Acceptance Evidence

- Status: Implemented on issue #51 branch
- Date: 2026-09-30
- Decisions: ADR-0011 and the format-neutral budget in `metadata-adapter-review.md`

## Behavior matrix

| Concern                   | PDF                                                                                                 | DOCX                                                                                               | DjVu                                                                        |
| ------------------------- | --------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| Detection                 | `%PDF-` in the bounded prefix after `.pdf` discovery                                                | Valid OPC Word main-document content type after `.docx` discovery                                  | `AT&TFORM` plus supported `DJVU`/`DJVM` form after `.djvu`/`.djv` discovery |
| Metadata                  | Info, selected XMP, conflicts, page count                                                           | Core and custom properties                                                                         | INFO/page facts and bounded uncompressed annotations                        |
| Provenance                | `apache-pdfbox` `3.0.8-1`                                                                           | `jdk-docx-opc` `1`                                                                                 | `jdk-djvu-iff` `1`                                                          |
| Encryption/active content | Encrypted input fails without metadata disclosure; actions/forms/media/attachments are not selected | Encryption fails; macros, ActiveX, OLE, embedded packages, and external relationships are rejected | Compressed annotation/text and image content are not decoded                |
| Execution boundary        | Staged PostgreSQL job and disposable worker child JVM                                               | Bounded in-process server adapter                                                                  | Bounded in-process server adapter                                           |
| User state                | `PENDING`, `READY`, or `FAILED_SAFE`                                                                | Ready or safe per-file failure                                                                     | Ready or safe per-file failure                                              |

The aggregate parser version is `bounded-metadata-v4`. A version change creates a new append-only observation set; unchanged files at the current version are skipped idempotently. Existing curated values are not overwritten.

## Resource and failure evidence

- Every source is admitted from no-follow file facts and the shared 4 GiB ceiling. No adapter calls a whole-file read API in production code.
- DOCX uses the shared 10,000-entry/4 MiB directory bounds, selected-entry expansion and compression-ratio checks, secure XML, 64 KiB selected scalars, and one monotonic read/deadline budget.
- DjVu uses positional reads with checked chunk arithmetic, bounded nesting/counts/scalars, and no image decompression. A generated 96 MiB sparse fixture requires less than 128 KiB of reads.
- PDF staging streams through a 64 KiB buffer, hashes while copying, and verifies size, modification time, file key, signature, and containment. The worker re-verifies size and SHA-256 before parsing.
- Each PDF parse receives a fresh child JVM capped at 384 MiB heap, 64 MiB direct memory, and a 35-second forced deadline. The container is non-root, read-only, capability-free, PID/CPU/memory constrained, attached only to an internal no-egress network, and has no database or source-root mount.
- Worker claims use PostgreSQL leases, three attempts, bounded request/result bodies, and stable safe error codes. Exhausted work becomes `FAILED_SAFE`; an unavailable worker leaves the Asset visibly `PENDING`.

## Fixture coverage

Fixtures are generated during tests and contain no private documents.

| Evidence                                    | Automated coverage                                                                                                       |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| Valid multilingual and conflicting metadata | DOCX and DjVu server adapter tests; PDF Info/XMP worker test                                                             |
| Malformed structures                        | DOCX ZIP/OPC, DjVu IFF, and PDF worker tests                                                                             |
| Encrypted/active content                    | encrypted DOCX/PDF, macro DOCX, and external-relationship DOCX tests                                                     |
| Near/over limits                            | exact and over-64-KiB scalar tests for all three formats; oversized sparse PDF admission test                            |
| Bounded work                                | sparse DjVu measured reads; PDF process/container ceilings; selected DOCX expansion/directory limits                     |
| Source preservation                         | byte-for-byte corpus preservation and PostgreSQL PDF staging lifecycle tests                                             |
| Queue/retry/catalog behavior                | PostgreSQL claim/input/result lifecycle, provisional state, search format filter, and original-download media-type tests |

Representative-image extraction is intentionally absent from this change. PDF/DjVu page rendering and DOCX thumbnail selection remain governed by ADR-0010 and issue #57. Full-text indexing, editing, macro execution, and conversion are also out of scope.

## Dependency inventory

The isolated worker includes PDFBox 3.0.8, pdfbox-io 3.0.8, FontBox 3.0.8, and Commons Logging 1.3.6. It excludes optional image codecs, Preflight, tools/examples, and Bouncy Castle. `services/yurlib-document-worker/THIRD-PARTY-NOTICES.md` records attribution, and packaged JAR `META-INF` notices remain intact.
