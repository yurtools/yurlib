# M2 Conversion Dependency and Packaging Review

- Status: Selected for M2 implementation
- Date: 2026-10-02
- Scope: issue #60, FB2 → EPUB and MOBI → EPUB only

## Selection

Yurlib pins calibre 9.15.0 and invokes its `ebook-convert` command only inside the optional isolated document worker. A route contract fixes the source and target formats, calibre version, arguments, output validation policy, resource profile, and fixture evidence. The presence of other calibre plugins does not create additional Yurlib routes.

The official calibre source declares `GPL-3.0-only`, which is compatible with Yurlib's GPL-3.0-only license. The project publishes signed SHA-512 manifests and corresponding source releases. Yurlib packages the unmodified official Linux binary bundle and retains calibre's copyright and third-party license inventory in the worker image.

| Architecture | Artifact                    | SHA-512                                                                                                                            |
| ------------ | --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| x86-64       | `calibre-9.15.0-x86_64.txz` | `77fb209188906a1be5ca1e8147339bd06242414ffbfe7dbc54e65667c8d6057fdc6e709efff0c415c42fb95ca5f53359ae041d5089cd9927a0d1d74d9fc8f4a5` |
| ARM64        | `calibre-9.15.0-arm64.txz`  | `725bae1ccbc5501df9cb92c1df4df9d335cd470dd8681c099bf7c1c7a0c05845f139a066c22dc38374c58fde0c4c2df21e9139e1a25d4917e0fd2183f2fcbafb` |

Primary references:

- <https://github.com/kovidgoyal/calibre/tree/v9.15.0>
- <https://github.com/kovidgoyal/calibre/blob/v9.15.0/pyproject.toml>
- <https://github.com/kovidgoyal/calibre/blob/v9.15.0/COPYRIGHT>
- <https://calibre-ebook.com/download_linux>
- <https://calibre-ebook.com/signatures/>
- <https://manual.calibre-ebook.com/conversion.html>

## Route contract

The initial identifiers are `FB2_TO_EPUB_V1` and `MOBI_TO_EPUB_V1`. Both use calibre 9.15.0 with fixed arguments for EPUB 3, the generic e-ink profile, and no generated default cover. Effective settings are part of the route key together with the verified source SHA-256 and route version.

Input is capped at 512 MiB and output at 1 GiB within the worker's 2 GiB quota-bound temporary filesystem. Each job has a ten-minute deadline, one CPU, 2 GiB container memory, a 64-process ceiling, bounded retries, leases, and heartbeats. These initial route limits are below the broader ingestion ceiling.

## Security and operational boundary

The worker runs as UID/GID 10001 with all Linux capabilities dropped, a read-only root filesystem, no database credentials, no source or managed-root mount, and only an internal network connection to the server. It never invokes a shell. The executable, options, route, and UUID-scoped paths are fixed.

The server stages and hashes an authorization-checked source, owns job state, streams input to the worker, receives a hash-declared output, validates EPUB structure and archive limits, rechecks cancellation, and atomically publishes to the configured managed conversion root. Only then does it create the derived Asset, location, and complete source lineage. Originals are never modified.

calibre is a large native dependency and expands the worker image and attack surface. That cost is accepted only inside ADR-0009's existing isolation boundary. Updates require a new pinned checksum, license review, representative corpus run, hostile-input regression, and route-version change when output can change.

## Container and representative-route evidence

The x86-64 worker image built successfully from the pinned artifact and ran as UID/GID 10001. `ebook-convert --version` reported calibre 9.15.0 under a read-only filesystem, dropped capabilities, no network, one CPU, 2 GiB memory, 64 PIDs, and a quota-bound temporary filesystem. The runtime packages `libxkbcommon0` and `libglx0` are pinned because calibre's bundled Qt runtime requires them; the calibre tree is owned by the non-root worker so its publisher-supplied private locale directory remains readable.

The route corpus used the repository-authored English, Cyrillic, and Japanese FB2 fixtures. All three converted with the fixed FB2 route arguments. A MOBI fixture generated from the authored English FB2 source then converted with the fixed MOBI route arguments. Every result passed ZIP integrity inspection, placed an uncompressed `mimetype` entry first, and retained readable metadata. The Cyrillic result retained `Кириллическая книга`, `Анна Тестова`, and language `rus`; the Japanese result retained language `jpn`; the MOBI result retained `Minimal FB2 Fixture`, `Yurlib Fixture`, and language `eng`.

SHA-256 checks before and after the run confirmed that all source FB2 fixtures remained byte-for-byte unchanged. Generated EPUB and intermediate MOBI artifacts were disposable and were not committed.
