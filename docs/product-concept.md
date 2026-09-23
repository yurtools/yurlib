# eLibrary — Product Concept

**Document ID:** PC-001  
**Version:** 0.2  
**Status:** Reviewed draft; pending owner approval  
**Date:** 23 September 2026  
**Working product name:** eLibrary  
**Authoritative repository path:** `docs/product/product-concept.md`

## Executive summary

Organize an existing EPUB, FB2 and MOBI collection without surrendering control of the files. Preserve original metadata, curate a coherent catalog, check configured external libraries such as Flibusta through replaceable connectors, and apply conversion and AI enrichment as explicit, recoverable background work.

> Review conclusion: the concept is usable. This revision clarifies identity, release scope, NAS safety, connector behavior, privacy and recovery while preserving the requested product capabilities.

## 01. Vision and scope

**PC-01** — A useful self-hosted product, developed through an auditable AI-assisted process.

eLibrary is a self-hosted electronic library manager for large collections stored in existing folders, including NAS-backed storage. It discovers EPUB, FB2 and MOBI files, builds a persistent catalog, reconciles inconsistent metadata, supports author merging, tags, collections, downloads and conversion, and connects to external electronic libraries through plugins.

The product must remain useful without AI, internet connectivity or any external connector. AI and remote libraries extend the local catalog; they must not become dependencies of ordinary browsing, curation or downloading of available local files.

### Primary users and initial assumptions

The initial user is an owner-curator operating a private collection. The same person may administer storage, connectors and credentials. Household readers and small organizational libraries are future audiences; multi-tenant hosting, public distribution and complex permission hierarchies are not assumptions for the first release.

The proposed first-release deployment is a Linux container host with local or host-mounted NAS paths and a browser-based interface. Whether that host is a NAS appliance or a separate server depends on its supported runtime and resources. Hardware support and minimum sizing belong in the environment design.

### Product principles

| Principle | Required outcome |
| --- | --- |
| Preserve originals | Index existing folders without reorganizing them. Separate source assets from generated files and curated metadata. |
| Preserve uncertainty | Represent unknown or conflicting metadata explicitly. A plausible match is not a verified identity. |
| Keep control with the owner | Make merges, external imports and file-changing operations explicit and auditable. Default to non-destructive behavior. |
| Make progress visible | Run expensive work in the background, expose partial results, and recover from interrupted jobs. |
| Extend without coupling | Add sources, formats, conversion engines and AI providers through defined boundaries rather than changes to the catalog core. |

### Two related but separate objectives

The product objective is a reliable, fast and convenient library. The engineering objective is a reference-quality Java microservices project using issue-driven AI implementation, deterministic verification and independent review. A feature should serve a product need; additional agents, services or infrastructure are not success measures by themselves.

## 02. Domain model and metadata

**PC-02** — Separate intellectual identity, publication details, binary content and physical location.

| Concept | Meaning and identity rule |
| --- | --- |
| Work | The underlying intellectual work. A translated title can refer to the same work without identifying the same edition. |
| Edition | A language, translation or publication-specific version. Record translator, publisher, identifiers, dates and abridgment when known; allow provisional records. |
| Asset | A particular ebook binary, including format, content identity and processing status. A conversion creates a new asset, not a new intellectual work. |
| Asset location | A stored occurrence of an asset: root and relative path, or managed-storage reference. Identical bytes at two locations remain separately traceable. |
| Contributor and alias | A person or organization associated with a work or edition in a role. Aliases are not globally unique identifiers; two people can share a name. |
| Metadata observation | A value reported by a file, parser, external source or inference, with provenance. A curated value is a separate user-controlled decision. |
| External record | A source-scoped identifier and metadata snapshot. Linking it to a local work or edition does not mean the file was imported. |
| Series, tag and collection | Series has ordering and membership; tags label works by default; collections group works by default. Edition and asset attributes remain distinct. |

### Provenance and curation

Retain original metadata, retrieval or extraction time, source identifier, parser or plugin version, and the matching decision where applicable. Track user edits, merge decisions and rejected suggestions. A rescan, external refresh or AI rerun must not overwrite a user-curated value silently.

### Matching must be conservative

Distinguish exact duplicate bytes, alternate formats of one edition, separate editions of a work, and unrelated books with similar titles. Title and author similarity create candidates; they do not establish edition identity. ISBN and other external identifiers are evidence, not mandatory fields or unconditional uniqueness guarantees.

An incompletely described file may enter the catalog as an unresolved or provisional record. Omnibuses and anthologies may contain several works; preserve that ambiguity instead of forcing a false one-file/one-work merge. Detailed multi-work cataloging can be expanded later without discarding the source observation.

> Example: an English EPUB and a Russian FB2 may belong to the same work, but different language editions. Matching them must not silently classify them as interchangeable copies.

## 03. Local library ingestion

**PC-03** — Discover quickly, enrich incrementally, and never confuse missing storage with deleted books.

### Storage modes

READ_ONLY roots allow discovery, parsing and download but no source writes. Managed output storage holds imported files, conversions and derived assets separately. IMPORT roots use a copy-first policy; deleting an incoming file requires a separate explicit policy. Reorganizing existing folders remains a product capability, with its rollout proposed for a later increment.

### Incremental pipeline

Discover candidates → record lightweight file facts → verify file stability → extract metadata → reconcile catalog records → perform deferred hashing and enrichment. Publish progress and provisional catalog entries before optional enrichment completes. Support EPUB, FB2 and MOBI at the first local-library milestone; classify unsupported, encrypted and corrupt files with actionable reasons.

Use root identity and relative location, file size, modification information and processing versions to avoid unnecessary work. Fast fingerprints and timestamps are change-detection hints, not proof of identical content. Compute a full content hash before declaring an exact duplicate; provide an explicit verification rescan for changes that cheap checks may miss.

### NAS and filesystem behavior

Provide manual and scheduled reconciliation scans. Filesystem notifications may accelerate discovery but must not be the sole correctness mechanism: Java does not require detection of changes made on remote systems. [S1](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/WatchService.html)

Detect an unavailable or wrong mount before reconciling absence. A failed or partial scan must not mark all previously seen assets as deleted. Confirm root identity and successful coverage before recording a location as missing; retain the catalog record and show availability separately. A later successful scan can restore availability.

Defer files still being copied or changed. Define handling for overlapping roots, symlinks, exclusions, case differences and Unicode paths. Never follow a path outside an authorized root. Treat a rename as a location change when verified; otherwise record the uncertainty rather than guess.

### Backpressure and recovery

Bound discovery, reads, parsing, hashing and writes independently. Persist pending work, retry state and progress. Support cancellation, pause/resume and restart recovery without duplicate catalog effects. A malformed file must not terminate the scan; failures should be inspectable and retryable without restarting the whole library.

> Basic scanning does not require cloud calls, AI, external-library lookups, full-text extraction or conversion. Those are separately scheduled workloads with their own limits.

## 04. Catalog curation and access

**PC-04** — Let the owner correct the library without losing source evidence or control of files.

### Browse, search and organize

Provide paginated or virtualized views for books, contributors, series, tags, collections and available assets. Search title, contributor aliases, series, identifiers, language, tags, filename, format and collection. Keep original Unicode text and support multilingual matching, including Cyrillic and Latin names; transliteration is a search aid, not an identity rule.

The book view shows known editions, available formats, original versus converted assets, metadata provenance and location availability. Unknown metadata must remain unknown rather than being filled with invented values. Full-text indexing, semantic search and natural-language queries are extensions, not prerequisites for metadata search.

### Author and contributor merging

Allow the owner to choose a canonical contributor and preview affected works, editions and aliases before merging. Preserve previous identities and source observations; keep stable redirects or equivalent history so rescans do not recreate the same duplicate contributor. Aliases must retain attribution and must not prevent another person from using the same name.

Record who merged, when, why, and which associations changed. Provide a reversible catalog operation or a guided split using the recorded pre-merge state. If later edits conflict, explain the conflict instead of blindly restoring obsolete data. Remember “not the same person” decisions to suppress repeated bad suggestions.

### Duplicate resolution

Show whether a candidate is an exact binary duplicate, another format, another edition or an uncertain match. Merging catalog records must not delete files. Reassigning an asset to a different edition should be reviewable and auditable. Physical deduplication and bulk deletion require separate, explicitly authorized operations.

### Download behavior

Download a selected available asset through an authorized application endpoint, using an asset identifier rather than a browser-supplied filesystem path. Show format, size and whether the file is original or converted. Stream the response without loading the entire asset into memory; provide a clear unavailable response for disconnected storage.

### Job visibility

Show discovery, extraction, hashing, conversion, external checks and AI enrichment as distinguishable jobs or stages. Expose totals when known, completed and failed counts, warnings, timestamps and retry options. Do not show a fabricated completion percentage before the amount of work is known.

> The default browsing unit is the work; edition and file details remain accessible. The UI must not require the owner to understand the full domain model to find and download a book.

## 05. External library workflows

**PC-05** — Checking an external catalog, linking metadata and acquiring a file are different operations.

### External-library ingestion plugins

Provide a first-class Library Connector extension point for Flibusta and other configured libraries, catalogs and metadata services. The core catalog must contain no Flibusta-specific assumptions. Each connector declares the operations actually available from its source and the configured account.

| Operation | Expected product behavior |
| --- | --- |
| Search | Query selected sources and return source-labeled candidates with pagination or an explicit coverage limit. Do not add them to the local catalog automatically. |
| Check a local book | Find possible matches, reported editions and formats. Show evidence and uncertainty; metadata presence does not prove that a file is retrievable. |
| Link or enrich | Associate a source record with a local work or edition, or propose metadata changes. Preserve provenance and existing curated values. |
| Import | On explicit request, retrieve an available, permitted asset into staging, validate it, and pass it through the normal ingestion pipeline. Do not bypass deduplication or security. |

### Availability is not a single Boolean

Keep match outcome, acquisition status and request status separate. A source can report a candidate while requiring authentication, lacking a download entitlement, timing out on format lookup, or returning cached information. Distinguish “no match found in this completed search” from “source unavailable,” “rate limited” and “search incomplete.”

Show source, external ID, checked time, stale/cached status, available metadata and reported formats. Explain whether the match is at work or edition level. Never label an edition “better” merely because it is newer, larger or in EPUB; present the observable differences.

### Flibusta and the first connector

Flibusta remains a specifically requested integration target. Its current endpoint, access method, authentication, automation policies and available operations are not verified by this concept. A feasibility task must establish those facts before promising a particular integration. No undocumented API, working mirror or download permission is assumed.

The proposed V1 scope includes the connector framework and at least one real external-library integration, with the first source selected after that feasibility check. A controlled OPDS source can serve as a repeatable test fixture. OPDS defines catalog and acquisition-link conventions; each implementation still needs capability checks. [S3](https://specs.opds.io/opds-1.2)

> External checks never trigger downloads implicitly. Automatic checks are opt-in; imports require user action in V1 and must respect source access controls, entitlements and applicable content permissions. DRM circumvention is outside scope.

## 06. Plugin contract and lifecycle

**PC-06** — Make plugins replaceable, observable and restricted without prematurely choosing a runtime framework.

### Capabilities and normalized results

A connector descriptor identifies the plugin, version, supported connector-API versions, source type, configuration schema and declared capabilities. Useful capabilities include search, lookup by supported identifiers, metadata, cover, formats, availability and import. Lookup by hash is optional and must not be assumed.

Normalize results into source-scoped records while preserving the source payload or a permitted snapshot. The contract must represent partial responses, continuation tokens, unsupported operations, authentication failures, throttling, temporary errors and permanent errors. Availability checks must identify their coverage and freshness.

### Configuration and operator control

Allow installation or registration, compatibility checks, configuration, connectivity testing, enable/disable and upgrade/rollback. Store credentials separately from ordinary catalog content and reference them securely. Expose only redacted configuration and diagnostics. Removing a plugin must not delete local books, accepted metadata or provenance.

Configure per-source timeouts, concurrency, retry limits, rate limits, cache lifetime and optional scheduled checks. Respect source limits and stop retrying permanent failures. An unavailable plugin must not block local ingestion or another connector. In-flight work needs a defined cancel, finish or retry policy when a plugin is disabled or upgraded.

### Trust and isolation

Treat remote records, URLs and downloaded assets as untrusted. Restrict connector egress to approved endpoints and validate redirect destinations; allow private library servers only through an explicit administrator-approved network policy. Do not allow a returned URL to become an unrestricted server-side request. These controls address server-side request forgery risks. [S4](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)

Define permissions for source reads, network access and staging writes. Plugins must not write catalog tables directly, mutate original books or obtain unrelated credentials. Untrusted executable plugins require an isolation boundary; an in-process interface alone is not an acceptable security boundary. The runtime and packaging choice belongs in an architecture decision.

### Testing and extension categories

Provide a connector conformance suite using deterministic fixtures for pagination, malformed responses, authentication errors, retries, cancellation, rate limits and import validation. Live-source tests are separate from required pull-request tests.

Library connectors are the initial externally extensible category. Format handlers, conversion providers, metadata enrichers and AI providers should have clean extension interfaces. A general plugin marketplace, hot loading and public SDK governance are not first-release requirements.

## 07. Conversion and AI assistance

**PC-07 / PC-08** — Optional automation must preserve both the original asset and the owner’s authority.

### PC-07 — Asynchronous conversion

Let the user select an available source asset and a supported target format. Execute conversion as a bounded background job and publish a new derived asset only after successful completion and checks. Preserve the original file, parent-asset reference, conversion engine/version and effective settings.

Calibre is a candidate conversion provider, not part of the catalog domain. Its documented inputs and outputs include EPUB, FB2 and MOBI, but its documentation does not guarantee that every generated EPUB is valid. Validate supported routes against a representative corpus and report conversion limitations. [S2](https://manual.calibre-ebook.com/faq.html)

Do not promise lossless round trips or universal device compatibility. Prevent repeated identical requests from producing accidental duplicates; identify a conversion by its source version and effective conversion configuration. Apply process time, memory and temporary-storage limits, avoid shell interpolation, and remove abandoned temporary output safely.

### PC-08 — Reviewable AI suggestions

The initial AI feature is optional contributor or external-record matching with an accept/reject workflow. Later capabilities include title normalization, series detection, tagging, summaries, semantic search and natural-language catalog queries. Rule-based processing remains available when no model is configured.

Every suggestion identifies the proposed change, supporting records, relevant source fields, provider/model and generation time. Similarity or ranking scores must not be presented as calibrated probabilities unless calibration has been demonstrated. Rejecting a suggestion should be remembered; accepting one becomes an ordinary auditable catalog change.

### Privacy, cost and permissions

Support local and cloud provider adapters without requiring a cloud account. Cloud processing is opt-in, with explicit control over whether metadata, excerpts or full text may leave the host. Default to metadata-only disclosure, redact credentials and private paths, and expose request counts, usage and configured spending or workload limits.

Treat book text, remote metadata and retrieved passages as data, not instructions. External content can contain indirect prompt-injection attempts; enforcement must occur outside the model through restricted tools, validated outputs and authorization checks. [S5](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html) AI must not directly merge records, delete files, change credentials or retrieve arbitrary URLs.

> Turning AI off must not remove accepted metadata or prevent ordinary use. AI failures and budget exhaustion pause enrichment, not local catalog operations.

## 08. Deployment, security and recovery

**PC-09** — Self-hosted must mean operable and recoverable, not merely containerized.

### Deployment boundary

Provide a documented container deployment with an explicit application-data volume, database persistence, read-only source mounts, writable staging/output storage and configuration. A multi-container stack is acceptable; one application container is not a mandatory architecture constraint. Mount NFS or SMB at the host or supported storage layer rather than requiring privileged mounts inside the application.

Keep database storage independent of ebook folders and on storage supported by the selected database. Report startup failures such as inaccessible roots, unwritable output directories and invalid configuration clearly. Keep source roots, generated outputs and staging non-overlapping or explicitly excluded to prevent ingestion loops.

### Access and untrusted files

Require authenticated owner access for the shared-network deployment, with authorization for administrative and file-changing actions. A deliberately configured loopback-only development mode may be simpler. Do not expose a public library or assume that LAN access is authorization. Document TLS or reverse-proxy setup for non-local access.

Treat books, archives, metadata, covers and conversion inputs as untrusted. Enforce archive expansion limits, safe XML parsing, path containment, file and image limits, conversion resource budgets and safe rendering of descriptions. A rejected file should be visible as a failure without exposing its active content.

Use least-privilege containers and scoped filesystem access. Keep secrets out of source control, logs, diagnostic exports and model prompts. Do not provide runtime plugins or development agents with the container host’s management socket or unrestricted production credentials.

### Backup, restore and upgrades

Document a consistent backup covering the catalog, curated metadata, merge history, configuration, required managed/imported assets and credential-recovery instructions. Identify derived caches that can be rebuilt. Existing read-only originals require their own storage backup; re-ingestion cannot reconstruct user curation or prove a backup is complete.

Test restore into a fresh deployment, including remapping library roots without losing asset identity. Version schema migrations and document upgrade recovery before release. Never promise that rolling back a container automatically rolls back an incompatible database migration.

### Observability

Expose health and readiness, structured diagnostics, queue depth, job failures, storage availability, processing throughput, connector status and AI usage. Correlate work from discovery through catalog publication. Keep payloads and paths out of broadly exposed logs unless explicitly needed and access-controlled.

> An unavailable NAS degrades file access; an unavailable connector degrades external checks; an unavailable AI provider degrades suggestions. None should erase catalog data.

## 09. Quality targets and acceptance

**PC-10** — Measure speed under defined conditions and test the failure cases that protect the library.

The design target remains 100,000+ ebook assets. This is a sizing objective, not a measured claim. “Superfast ingestion” means early useful results, incremental processing, bounded resource use and sustained throughput without making the library UI unusable.

| ID | Proposed acceptance measure |
| --- | --- |
| NFR-01 | Repeated scans and retry/restart tests produce no duplicate catalog effects for an unchanged asset location. |
| NFR-02 | A completed unchanged rescan reparses zero unchanged ebook payloads unless verification is requested or the extraction version invalidates prior results. |
| NFR-03 | Disconnecting a root or interrupting a scan marks the root unavailable/incomplete; it does not delete records or declare all files removed. |
| NFR-04 | Search and catalog-list APIs target p95 ≤ 2 seconds at 100,000 assets during a bounded scan, on an agreed reference environment. |
| NFR-05 | A scan request returns a job identifier without waiting for scanning. First provisional results target ≤ 60 seconds on an agreed corpus with accessible files. |
| NFR-06 | The same read-only source corpus is byte-for-byte unchanged after scan, curation, download and conversion tests. |
| NFR-07 | Connector timeout, rate-limit, no-match and partial-result states are distinguishable, and local-library operations still succeed. |
| NFR-08 | A tested backup restores curated fields, aliases, tags, history and required managed assets into a fresh deployment. |

### Benchmark contract

Before adopting numeric release gates, record CPU, RAM, database placement, NAS protocol, network rate, cache state, concurrency and software versions. Version a representative corpus with known file counts, sizes, formats, directory shapes, languages, duplicates and malformed files. Report cold and warm scans separately.

Measure discovery, metadata extraction, full hashing and AI enrichment separately: files/second, bytes read, time to first result, time to usable catalog, error counts, peak resource use and interactive latency. Do not hide hashing or enrichment work inside an unexplained “ingestion complete” metric. Hardware-dependent throughput targets are set after the first benchmark, not invented here.

### Safety and identity regression corpus

Include partial copies, renamed files, unreachable roots, symlinks, malicious archives, duplicate bytes, ambiguous authors, translated editions and conflicting source metadata. Use generated or appropriately licensed fixtures; private books do not belong in a public repository.

> Numeric latency targets above are review proposals, not validated performance results. Correctness, source preservation and recovery remain mandatory even when throughput is below target.

## 10. Delivery and AI engineering

**PC-11** — Deliver usable slices; keep the product baseline separate from the development-environment design.

The following phasing is a proposal, not a removal of requested capabilities. V1 is the combined usable product across M1–M3; M1 alone is an early milestone, not a claim that the complete concept has shipped.

| Stage | Outcome |
| --- | --- |
| M0 — Foundation | Create the GitHub repository, install local Plane, write and review the environment/SDLC design, configure the workflow and derive its implementation tasks. |
| M1 — Local library | Container deployment; read-only roots; EPUB/FB2/MOBI discovery and metadata; resumable ingestion; catalog search; progress/errors; authenticated access and original downloads. |
| M2 — Curation | Author aliases and manual merge with history/recovery; tags and collections; duplicate review; supported format conversion; managed output storage; backup/restore verification. |
| M3 — Connected library | Versioned connector contract; at least one real source; Flibusta feasibility and implementation when supported; external search/check/link; supported user-triggered import; one optional AI suggestion workflow. |

### Follow-on scope

Preserve these as later candidates: broad managed-folder reorganization; automatic acquisition policies; additional formats and connectors; richer multi-work editions; full-text and semantic search; advanced recommendations; additional users and device integrations. DRM circumvention, public multi-tenant hosting, a plugin marketplace and Kubernetes are not V1 requirements.

### Architecture direction, not topology lock-in

The intended engineering direction remains Java/Spring microservices with a web frontend. Catalog, ingestion, conversion, connectors and AI are logical responsibilities, not a mandated count of independently deployed services. Define process boundaries, state ownership, contracts and failure behavior in the system design; choose versions, infrastructure and service count through recorded decisions.

### AI-assisted development acceptance

Keep this concept and approved design decisions in GitHub; Plane tracks implementation work rather than replacing the design baseline. Tasks reference stable concept identifiers, the relevant document revision, acceptance criteria and evidence. Each change follows plan → implementation owner → deterministic tests/CI → independent review → human acceptance → merge and release.

Parallel implementation uses bounded ownership and isolated worktrees. Reviewers check requirements, integration behavior and security, not merely style. Agents may propose architectural changes but must not silently introduce new infrastructure or bypass release authority. Test fixtures, connector contracts and failure scenarios provide objective evidence for reviewing generated code.

## 11. Review log and open decisions

**PC-12** — The concept is viable; the reviewed draft makes its boundaries and risks explicit.

### Material revisions from the original concept

| Review finding | Revision in this document |
| --- | --- |
| Product and design were mixed | Retained architectural direction but removed fixed service topology and implementation-interface code from the concept. |
| The first release was too ambiguous | Separated an early usable local-library milestone from the complete proposed V1. Retained connectors, conversion and optional AI. |
| File and book identity needed more detail | Added asset locations, provisional editions, translation/anthology ambiguity and non-unique author aliases. |
| NAS failure could resemble deletion | Added root identity checks, completed-scan reconciliation, explicit availability and no automatic record deletion. |
| External checks were underspecified | Separated search, matching, availability, metadata linking and import; kept Flibusta explicit without inventing live capabilities. |
| Automation lacked operating boundaries | Added plugin lifecycle, network policy, cloud-AI consent, conversion checks, backup/restore and measurable acceptance targets. |

### Decisions to resolve in subsequent design work

D-01 — Naming: eLibrary remains a working product name; confirm the repository and public application name before publication.

D-02 — Deployment: choose reference hardware, supported CPU architectures, NAS protocol and minimum runtime resources for testing and packaging.

D-03 — Scope: approve the M1–M3 phasing, access model, managed-folder rollout and the tested conversion-route matrix. Explicitly decide whether .fb2.zip archives enter V1.

D-04 — Connectors: validate Flibusta feasibility and select the first real source, supported OPDS version(s), permitted operations and network policy. Define the fallback when a target source cannot be supported.

D-05 — Plugin execution: decide trusted/bundled versus separately isolated execution, compatibility rules and upgrade behavior before implementing the public extension contract.

D-06 — AI and distribution: choose the initial provider adapter and suggestion workflow, confirm data-disclosure defaults, and decide application licensing and third-party packaging obligations.

> Approval of this concept establishes product intent. It does not approve every technology, deadline, live connector, numeric benchmark or deployment decision. Those require subsequent design and evidence.

## 12. Sources and validation record

**REFERENCE** — Primary references support feasibility and risk notes, not claims that the product is implemented.

Review date: 23 September 2026. The source concept is the product description and draft supplied in this conversation. External references below were consulted only for the specific technical checks stated; requirements and delivery proposals are authoring decisions.

### S1 — Oracle — Java SE 25 WatchService documentation

[Oracle — Java SE 25 WatchService documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/WatchService.html)

Confirms that detection of changes made on remote systems is not required and that change notifications do not guarantee a file writer has finished. Used to justify reconciliation scans and stable-file checks.

### S2 — Calibre — Frequently Asked Questions, ebook format conversion

[Calibre — Frequently Asked Questions, ebook format conversion](https://manual.calibre-ebook.com/faq.html)

Documents EPUB, FB2 and MOBI among supported input/output formats and explains limitations on EPUB validity. This is a feasibility reference; no specific engine version or conversion quality has been accepted.

### S3 — OPDS Community — OPDS Catalog 1.2 specification

[OPDS Community — OPDS Catalog 1.2 specification](https://specs.opds.io/opds-1.2)

Provides catalog, metadata and acquisition-link conventions for connector design. This review does not establish which protocol version or optional capabilities any named external library currently supports.

### S4 — OWASP — Server Side Request Forgery Prevention Cheat Sheet

[OWASP — Server Side Request Forgery Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)

Supports destination restrictions and redirect controls for connector requests. Private-library access must be explicitly configured rather than permitted through arbitrary remote URLs.

### S5 — OWASP — LLM Prompt Injection Prevention Cheat Sheet

[OWASP — LLM Prompt Injection Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)

Describes malicious instructions embedded in external content and defense-in-depth controls. Used to separate ebook/metadata analysis from privileged application actions.

### Validation limits

No production code, external connector, conversion corpus, performance benchmark or restore procedure was executed for this review. Flibusta availability, interfaces, access requirements and policies remain unverified. This document is a reviewed draft for decision-making and implementation planning, not a certification of operational readiness.

### Document authority

Recommended authoritative repository file: docs/product/product-concept.md. The Word file is the corresponding review/export copy. After approval, update the version and review status together and keep both representations synchronized; do not maintain conflicting design baselines in the tracker.
