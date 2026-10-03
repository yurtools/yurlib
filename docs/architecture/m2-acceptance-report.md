# M2 Acceptance Report

## Status

M2 automated acceptance and the destructive local recovery drill are complete on branch `feat/61-m2-acceptance`. Final pull-request CI and owner-facing desktop/mobile acceptance remain release gates until the branch is merged.

M2 supports PostgreSQL only. NFS/NAS compatibility and performance evidence remains deferred to post-M2 issue [#70](https://github.com/yurtools/yurlib/issues/70); this report makes no network-storage claim.

## Walking-skeleton evidence

The M2 walking skeleton is covered as one traceable acceptance flow across focused integration suites rather than one large order-dependent test:

| Flow stage                                                                                | Objective evidence                                                                                                                           |
| ----------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Persisted owner, second user, capabilities, two source roots, managed root, and root deny | `M2AuthorizationAcceptanceTest.enforcesTwoUserRootProjectionCapabilitiesAndImmediateSessionInvalidation`                                     |
| Bounded parallel scan and unchanged original downloads                                    | `M1WalkingSkeletonAcceptanceTest.configuresScansRescansAndDownloadsOriginalBytesWithoutChangingSources` and `StagedIngestionIntegrationTest` |
| EPUB, FB2, MOBI, PDF, DOCX, and DjVu metadata                                             | `BoundedMetadataExtractorTest`, `PdfMetadataQueueIntegrationTest`, the generated fixture corpus, and the reports linked below                |
| Provenance-aware curation and conflict handling                                           | `YurlibServerIntegrationTest`, `DefaultCatalogCandidateReconcilerTest`, and `CatalogRecoveryIntegrationTest`                                 |
| Favorite contributor, read evidence, and private collection                               | `PersonalLibraryIntegrationTest.keepsPersonalStatePrivateVersionedAndHiddenWithoutDeletingMembership`                                        |
| Managed cover publication                                                                 | `CoverQueueIntegrationTest.publishesValidatedCoverAtomicallyAndExposesOnlyTheOpaquePath`                                                     |
| FB2/MOBI conversion, validation, lineage, publication, and retry safety                   | `ConversionQueueIntegrationTest` and isolated-worker contract tests                                                                          |
| Merge, undo, guided split, stable survivors, and private-state reconciliation             | `CatalogRecoveryIntegrationTest` plus the manual acceptance recorded in `docs/logs/0031-m2-merge-split-recovery-merged.md`                   |
| PostgreSQL and managed-root backup/restore                                                | `ops/tests/test_yurlib_recovery.py` plus the disposable drill below                                                                          |

This decomposition keeps failure diagnostics local while the table preserves the end-to-end requirement trace.

## Security and non-disclosure

Automated tests cover:

- default user visibility and explicit root denies;
- mixed-root Works and transitive derived-asset lineage;
- catalog totals, direct asset and job identifiers, original downloads, covers, conversions, reviews, favorites, read state, and private collection contents;
- independent ingestion and curation capabilities;
- immediate session invalidation after permission or owner-password changes;
- immutable security and curation audit history;
- CSRF, optimistic versions, opaque managed paths, safe archive/XML/image/document parsing, and bounded worker inputs.

Denied resources return the same non-disclosing outcomes as absent resources. Personal-state rows remain owned by their user and become invisible, rather than being deleted, when a root deny removes the corresponding catalog visibility.

## Recovery drill

On 2026-10-02, a destructive drill used two uniquely named disposable Compose projects and non-default ports. The source deployment contained:

- one persisted owner;
- one available read-only source root and one available managed-output root;
- original EPUB and FB2 assets, one derived EPUB asset, locations, and explicit derivation lineage;
- one managed file;
- queued scan, ingestion, cover, and conversion work.

The application was stopped before backup. `ops/yurlib_recovery.py backup` produced a PostgreSQL custom-format dump, root inventory, lineage export, managed file tree, and version-1 manifest. Independent `verify` passed.

Restore ran against a fresh PostgreSQL volume and an absent managed-root target. Results:

| Assertion                                                            | Result                                                                                                    |
| -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Restored server starts and reports healthy                           | Pass                                                                                                      |
| Owner account preserved                                              | Pass                                                                                                      |
| Managed root remains available                                       | Pass                                                                                                      |
| Read-only source root requires revalidation                          | Pass; root and source locations are `UNAVAILABLE`                                                         |
| Managed bytes preserved                                              | Pass; source and restored SHA-256 both `c087dec111f4d337ca6573016e29bf1e95624bc50822af0900bdbc271675454e` |
| Derivation and location lineage exported and database state restored | Pass                                                                                                      |
| Interrupted scan and ingestion                                       | Pass; both become `CANCELLED` with restore diagnostics                                                    |
| Interrupted cover and conversion work                                | Pass; both become `FAILED_SAFE` with `RESTORE_INTERRUPTED`                                                |
| Duplicate or partial publication                                     | None observed                                                                                             |
| Disposable resources                                                 | Containers, networks, volumes, temporary data, and ports removed; temporary files moved to desktop trash  |

The first restore attempt began immediately after the database container entered its running state and correctly failed without changing the empty target because PostgreSQL was not ready. The tool now waits up to 30 seconds for `pg_isready`; the repeated restore completed successfully.

## Manifest safety

The dependency-free recovery tests prove rejection of:

- changed managed bytes;
- unexpected backup files, including accidentally copied source content;
- `..` traversal in manifest paths;
- symbolic links in managed trees;
- missing or invalid manifest content.

They also prove exact file bytes and permission modes are reproduced in a new tree. Restore verifies all database/export hashes and the exact managed-root inventory before modifying PostgreSQL.

## Performance interpretation

`MetadataReferenceBenchmarkTest` and `IngestionReferenceBenchmarkTest` report bytes read, allocation, heap observations, elapsed time, throughput, outcome counts, and queue behavior for the executing workstation and corpus. These are reference observations, not universal capacity or network-storage claims. Resource-bound tests and queue tests are the correctness gates.

## Related evidence

- [M1 acceptance report](m1-acceptance-report.md)
- [M2 metadata resource evidence](m2-metadata-resource-evidence.md)
- [M2 PDF, DOCX, and DjVu evidence](m2-pdf-docx-djvu-evidence.md)
- [M2 ingestion benchmark evidence](m2-ingestion-benchmark-evidence.md)
- [M2 conversion dependency review](m2-conversion-dependency-review.md)
- [M2 backup and restore runbook](m2-backup-restore-runbook.md)

## Final owner acceptance

Before merging the M2 release branch, repeat the visible workflows at 1440×900 and 390×844:

1. Sign in as owner and as a restricted reader.
2. Confirm the owner can see ingestion and curation controls and the reader cannot.
3. Apply a root deny and confirm denied Works disappear from results, totals, direct links, downloads, jobs, covers, conversions, favorites, read state, and collection contents.
4. Confirm allowed search, pagination, original download, cover display, conversion request/status/download, favorite, read state, and collection editing remain usable.
5. Exercise merge preview, merge, automatic undo, and guided-split messaging.
6. Sign out and verify no private state remains visible; sign back in and confirm it persists.
7. Use keyboard-only navigation, visible focus, and form submission; check for overflow, clipping, overlap, inaccessible labels, Yurlib console errors, and unexpected backend errors.

Record the tested commit and both viewport results in the pull request. Do not mark final visual acceptance complete until every item passes.
