# M1 Walking-Skeleton Acceptance Report

- Status: Complete
- Date: 2026-09-29
- Scope: Issue [#22](https://github.com/yurtools/yurlib/issues/22)
- Design authority: ADR-0003, ADR-0004, and `local-library-vertical-slice.md`

## Automated evidence

| Scenario                     | Evidence                                                                                                                                                                                                                                                                                                                                   |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Owner access                 | `OwnerSecurityConfigurationTest` verifies public session discovery, correlated 401 Problem Details, real cookie/header CSRF, successful and failed login, owner session use, and protection of root, scan, and sensitive actuator routes.                                                                                                  |
| Loopback bypass              | `LoopbackDevelopmentSecurityConfigurationTest` accepts loopback addresses and rejects blank, wildcard, non-loopback, and invalid binds.                                                                                                                                                                                                    |
| Happy path                   | `M1WalkingSkeletonAcceptanceTest` configures a marker-verified root, queues and runs a scan against PostgreSQL, finds all four valid EPUB/FB2/MOBI fixtures, and reads original bytes through the opaque asset identifier.                                                                                                                 |
| Idempotent rescan            | `M1WalkingSkeletonAcceptanceTest` runs the same complete scan again, asserts zero processed and four skipped files, and verifies that asset identifiers are unchanged. It also covers PostgreSQL microsecond timestamp rounding.                                                                                                           |
| Restart recovery             | `YurlibServerIntegrationTest.reclaimsOnlyAnExpiredRunningJob` verifies that an unexpired lease is not stolen and an expired running job is reclaimed with its original start time. Transactional reconciliation and the walking-skeleton rescan verify repeatable catalog effects.                                                         |
| Unavailable or wrong root    | `FilesystemRootVerifierTest.reportsMissingAndMismatchedMarkersDistinctly` and `ScanJobWorkerTest.recordsASafeFailureWhenExecutionCannotContinue` verify fail-closed identity behavior and stable safe failure codes.                                                                                                                       |
| Partial parser failure       | `ScanJobWorkerTest.persistsEachOutcomeAndReconcilesOnlyAfterCompleteCoverage` records one file failure while a valid candidate is processed and complete coverage permits reconciliation.                                                                                                                                                  |
| Unstable file                | `BoundedMetadataExtractorTest.defersMetadataWhenFileFactsChangeDuringParsing` and `FilesystemAssetFileOpenerTest.rejectsAFileThatChangedAfterCataloging` prevent unstable content from being published or downloaded as unchanged.                                                                                                         |
| Traversal and symlink safety | `FilesystemRootVerifierTest.rejectsTraversalAbsoluteBackslashAndEscapingSymlinkPaths`, `FilesystemScanDiscoveryTest.discoversSupportedFilesWithoutFollowingSymbolicLinks`, and the asset-opener symlink tests enforce containment.                                                                                                         |
| XXE and archive limits       | `BoundedMetadataExtractorTest.rejectsMalformedAndExternalEntityFb2WithoutResolvingTheEntity` and `rejectsUnsafeAndExpandingEpubEntries` cover XXE, traversal entries, and decompression bounds.                                                                                                                                            |
| Download safety              | `DefaultOriginalAssetContentServiceTest` distinguishes missing and unavailable assets; `FilesystemAssetFileOpenerTest` revalidates identity, containment, file facts, and streaming; controller tests require an opaque UUID rather than a path.                                                                                           |
| Source preservation          | `FixtureCorpusTest.materializesEveryRequiredFixtureWithoutChangingItsSources`, `BoundedMetadataExtractorTest.doesNotModifyFixtureSourceBytes`, and the final assertion in `M1WalkingSkeletonAcceptanceTest` compare SHA-256 maps before and after use.                                                                                     |
| Observability                | `MicrometerScanJobTelemetryTest` verifies bounded parser-failure tags, queue depth, job/file counters, and duration. `ScanJobWorkerTest` verifies lifecycle telemetry calls. Production logs contain correlation, job/root IDs, state, duration, counts, and stable error code only—never physical paths, credentials, or identity tokens. |
| Contract and Angular         | `OpenApiContractTest`, controller tests, `LibraryApi` tests, and `App` tests verify the session contract, relative URLs, password clearing, authentication gating, bounded polling, failure display, pagination, and download by asset ID.                                                                                                 |

## Reference environment observations

These observations describe one development workstation run. They are not latency objectives, capacity claims, or release guarantees.

- Workstation: Linux, Java 26.0.2.1 targeting Java 25 bytecode, Docker 29.7.2, PostgreSQL 18 Alpine through Testcontainers.
- Focused walking-skeleton command: `./mvnw -pl services/yurlib-server -Dtest=M1WalkingSkeletonAcceptanceTest,DefaultCatalogCandidateReconcilerTest,FilesystemAssetFileOpenerTest test -q`.
- Observed wall time: approximately 30 seconds, including Maven startup, compilation checks, JVM startup, Testcontainers/Ryuk startup, PostgreSQL startup, Spring context startup, migrations, and tests. This is not an isolated scan benchmark.
- The acceptance root contained four valid supported-format files. The first scan processed four; the immediate unchanged rescan processed zero and skipped four.

## Human acceptance

The owner-access workbench passed final visual verification at 1440×900 and 390×844. Verified states included initial loading, signed-out login, incorrect credentials, password clearing, keyboard sign-in, authenticated owner identity, the configured source, search, empty catalog, pagination, footer, sign-out, refresh in both session states, and visible keyboard focus. Both viewports had no overflow, clipping, overlap, unusable spacing, or Yurlib console errors. The only console message was the built-in Electron browser's generic development CSP warning.

The visual run did not start a library scan or alter the configured source. Scan execution, progress/failure behavior, catalog reconciliation, original download, and unchanged rescan are covered by the automated evidence above.

## Completion gate

- Full repository verification: passed (`./mvnw verify`, 88 tests; formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs gates all passed).
- Frontend and deployment verification: passed (clean npm install with no reported vulnerabilities, 9 Angular tests, production build, and `docker compose config`).
- Updated owner-access visual verification: passed at 1440×900 and 390×844; the automated walking-skeleton workflow verification also passed.
- CI pull-request checks: passed on pull request #40 (backend, frontend, Compose configuration, and dependency review).
