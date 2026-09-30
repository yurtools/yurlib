# M2 PDF, DOCX and DjVu Targets

- Status: In progress
- Date: 2026-09-30
- Branch: `docs/41-add-pdf-docx-djvu`
- Design issue: [#41](https://github.com/yurtools/yurlib/issues/41)
- Format issue: [#51](https://github.com/yurtools/yurlib/issues/51)
- Repository: `yurtools/yurlib`

## Prompt

> add adding pdf,docx,and djvu files to m2

## Plan

1. Add PDF, DOCX and DjVu discovery, bounded metadata extraction, cataloging, search and original download to the authoritative M2 product outcome.
2. Keep full-text indexing, editing, macro execution, active-content execution and unverified conversion routes outside this scope.
3. Create a dedicated M2 issue for the three formats and make it a sub-issue of Design M2 issue #41.
4. Require the common M2 resource-budget contract to cover all six supported formats without expanding issue #49 beyond its correction of the three existing M1 adapters.
5. Update the M2 milestone and Design M2 issue, add the new issue to the Yurlib Engineering project in Todo, and verify the synchronized records.

## Safety and architecture boundaries

- PDF parsers and renderers must not execute JavaScript, launch actions, embedded files or network references.
- DOCX handling must treat the ZIP package and relationships as untrusted, prohibit macro or active-content execution, and never fetch external relationships.
- DjVu parsing or rendering must use bounded reads and decompression, with reviewed dependencies or an explicitly isolated external tool.
- Format detection must use validated content structure rather than trusting only the filename or declared media type.
- Original files remain read-only and are downloaded through the existing asset authorization and containment boundary.
- Adding catalog support does not silently approve full-text indexing or a conversion route.

## Actions and results

- Added the three formats to the M2 outcome in the product concept and README while preserving EPUB/FB2/MOBI as the completed M1 baseline.
- Explicitly limited this target to discovery, bounded metadata, catalog/search behavior, safe failures, and original downloads. Full-text indexing, editing, active-content execution, and unapproved conversion routes remain outside the target.
- Created issue [#51](https://github.com/yurtools/yurlib/issues/51), assigned it to milestone `M2 — Curation`, labeled it `engineering` and `m2`, made it a sub-issue of Design M2 issue #41, added it to the Yurlib Engineering project, and set its status to Todo.
- Added format-specific PDF, DOCX, and DjVu threat boundaries and acceptance criteria to issue #51.
- Updated issue #41 to require issue #51, include all six formats in normalization and resource-budget design, and cover parser/renderer dependencies, active content, hostile fixtures, and isolation decisions.
- Retitled issue #49 to clarify that it corrects the existing M1 EPUB/FB2/MOBI adapters and linked it to issue #51's use of the shared format-neutral resource-budget contract.
- Updated the M2 milestone description to include secure PDF/DOCX/DjVu catalog support.

## Verification

- Confirmed issue #51 is open in the M2 milestone, is a sub-issue of #41, and is in Yurlib Engineering status Todo.
- Confirmed the M2 milestone contains three open issues: #41, #49, and #51.
- Confirmed issue #41 distinguishes existing-format correction (#49) from new-format support (#51).
- `./mvnw -B verify` passed 91 tests plus formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs checks.
- `git diff --check` passed.
- Frontend tests/build and Compose validation were not rerun because this change affects only Markdown planning documents and GitHub work records; the unrelated local Angular analytics preference was preserved unchanged.
