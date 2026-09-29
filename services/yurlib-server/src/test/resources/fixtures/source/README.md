# Yurlib Local-Library Fixture Sources

These small fixtures were authored for Yurlib and are distributed under the repository's GPL-3.0-only license. They contain no private book content.

The textual sources are immutable inputs. `FixtureCorpus` copies them and generates the binary/path fixtures in a temporary directory for tests. Tests can call `sourceHashes()` before and `assertSourcesUnchanged()` after an acceptance scenario.

Generated cases:

- `valid/minimal.epub`: minimal EPUB structure;
- `valid/minimal.mobi`: minimal uncompressed PalmDOC/MOBI structure;
- `security/traversal.epub`: ZIP entry that attempts `../` traversal;
- `security/decompression-limit.epub`: highly compressible expanded content;
- `security/symlink-escape`: symlink that resolves outside the generated library root.

Committed textual cases cover valid FB2, malformed XML, an external-entity declaration, and a Unicode path.
