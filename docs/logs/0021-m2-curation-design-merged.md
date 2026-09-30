# M2 Curation Design

- Status: Merged
- Started: 2026-09-30
- Branch: `docs/41-m2-curation-design`
- Pull request: [#62](https://github.com/yurtools/yurlib/pull/62)
- Design commit: `7ba599f`
- Squash-merge commit: `8438dbb`
- Design issue: [#41](https://github.com/yurtools/yurlib/issues/41)
- Repository: `yurtools/yurlib`

## Initiating prompt

> merged. start with your recomended start, lets complete the first deliverables list. Ask questions that need to be resolved

## Plan

1. Produce `docs/architecture/m2-curation-design.md` as the integrated design context, component map, contracts, threat model, acceptance matrix, and walking-skeleton plan.
2. Record expensive-to-reverse decisions as ADRs before dependent implementation begins.
3. Define the canonical Work/Edition/Asset and metadata-provenance model.
4. Define multi-user identity, root visibility, personal library state, and authorization semantics.
5. Define bounded parsing, cover rendering, parallel ingestion, conversion isolation, managed storage, and PostgreSQL-backed job behavior.
6. Derive an ordered, independently reviewable M2 issue backlog with objective acceptance evidence.
7. Preserve the M1 source-safety, authentication, compatibility, and original-download guarantees.

## First owner decision questions

The following questions were asked because their answers change persistent state ownership, authorization, deployable topology, or compatibility commitments:

1. Should PDF, DOCX, and DjVu represent general library documents or only book-like content?
2. Should M2 persist only one owner profile or introduce multiple users?
3. Does read/unread state belong to Work, Edition, or both?
4. Which additional relational database engine should M2 support?
5. Should conversion run in a dedicated optional container, inside `yurlib-server`, or be deferred?
6. Which conversion routes should M2 initially support?
7. Should managed output use one writable root with namespaces or independently configured roots?
8. Should PDF/DjVu representative pages be rendered and should DOCX package thumbnails be used as covers?
9. What minimum deployment environment should define resource defaults and benchmarks?

## Owner response

> 1: A ,
> 2: split injection vs library access.  Injestion source management is a flag given to a user at creation. Owner has it by default. When user created his ability to see books could be limited by explicit "deny" on an injectiion root. By default all users can see all roots.
> 3: A
> 5: A is good, keep a conversion queue in the DB
> 6: A
> 7: see #2, multiple roots
> 8: use recommended target
>
> update logs with the questions and choices, for #4, why do we need it? Supporting just PostgreSQL is fine for m2

## Decisions recorded

| Area | Owner decision | Design consequence |
| --- | --- | --- |
| Catalog semantics | General library documents | All six supported formats use the Work → Edition → Asset model; a content kind distinguishes books from other documents. |
| Identity and source administration | Multiple persisted users; ingestion-source management is a user capability granted at creation; the owner has it by default | M2 supersedes ADR-0004's M1-only single-owner scope through a new identity and authorization ADR. |
| Root visibility | Users can see all roots by default; an explicit per-user deny on an ingestion root hides its books | Authorization must be enforced in catalog queries, details, search, downloads, covers, personal state, and derived-asset access. |
| Read state | Work-level | A user's read/unread state belongs to the canonical Work; the completed Edition can be retained as evidence without becoming a second state authority. |
| Database | PostgreSQL only for M2 | Additional database engines and compatibility CI are deferred; PostgreSQL owns users, permissions, catalog state, ingestion jobs, conversion jobs, and transactional claims. |
| Conversion topology | Dedicated optional converter container | The converter receives narrow jobs, constrained mounts and resources, no network by default, and cannot mutate originals or catalog tables directly. |
| Conversion queue | Durable PostgreSQL queue | Conversion requests, claims, heartbeats, retries, cancellation, outcomes, and idempotency keys survive application and worker restarts. |
| Initial conversion routes | FB2 → EPUB and MOBI → EPUB | PDF, DOCX, DjVu, reverse, and other routes require later route-specific approval and acceptance fixtures. |
| Storage | Multiple configured roots integrated with root access control | Source and managed-output roots need explicit modes, ownership, visibility, containment, and derived-asset authorization rules. |
| Document covers | Recommended bounded behavior | Render PDF/DjVu page one; use a DOCX package thumbnail when present; otherwise use a generic fallback. Store a normalized metadata-stripped derivative with provenance. |

## PostgreSQL-only rationale

No additional database engine is required for M2 functionality. Multi-engine support had been listed as an earlier owner-requested target, but it would multiply migration, generated-key, timestamp, locking, durable-job claim, search, backup/restore, and CI work. Keeping PostgreSQL as the single supported M2 database reduces risk while M2 adds multiple users, root authorization, bounded parallel ingestion, and a conversion worker. Database portability remains a later candidate and is not implied by a configurable JDBC URL.

## Open decisions

The first follow-up asked who administers users and source-management grants, the precise ingestion-source-manager scope, owner-credential migration, mixed allowed/denied Work projection, derived-asset authorization, root modes, mutable permission grants, and the benchmark reference environment.

### Owner response

> 1: A,
> 2: A,
> 3: A,
> 4: Accept
> 5: Accept
> 6: Accept
> 7: yes
> 8: Approve

### Decisions recorded

- Only the owner creates or disables users, grants or removes ingestion-source management, and manages root denies.
- An ingestion-source manager can configure or remove roots, verify mounts, start or cancel scans, and inspect ingestion errors. The capability does not permit user, permission, credential, or personal-state administration.
- M2 bootstraps a persisted owner from the deployment-defined credential on first shared-network startup. The database credential becomes authoritative afterward, with a controlled operator recovery procedure.
- A Work is visible when it has at least one asset on an allowed root. Denied assets, locations, observations, downloads, and provenance are hidden. Shared canonical/curated metadata remains visible for an otherwise-visible Work. A denied-only Work is absent from search, counts, collections, and direct lookup.
- A derived asset is visible only when the user may access both its source lineage and its managed-output root.
- Initial root modes are `READ_ONLY_SOURCE` and `MANAGED_OUTPUT`. Each root has one mode; source managers configure both; import/copy-in roots remain deferred.
- The owner can change ingestion-source-management grants and root denies after account creation. Changes are audited, authority reductions invalidate existing sessions, and authorization takes effect immediately.
- The M2 reference environment is 4 CPU cores, 8 GiB RAM, x86-64 and ARM64, local SSD plus SMB and NFS coverage, and one Yurlib application instance.

## Remaining decisions

The final follow-up asked who may edit shared catalog data and whether tags and collections are shared or private.

### Owner response

> 1A, 2A

### Decisions recorded

- `CURATE_CATALOG` is a separate grantable capability. The owner has it by default and may grant or revoke it independently of ingestion-source management. Curators manage canonical metadata, contributor aliases, merge/split operations, duplicate decisions, and shared tags.
- Tags are shared catalog taxonomy managed by curators. Collections are private per user. If root access is removed, hidden Works remain referenced internally but disappear from the user's visible collection contents and counts until access returns.
- No owner-level design decisions remain open for the first M2 deliverables.

## Actions and verification

- Design M2 issue #41 moved from Todo to In Progress.
- Pull request #52 and its planning log were reconciled as merged at `556db0f`.
- The product M2 summary now specifies multi-user root access, PostgreSQL-backed conversion queues, and defers additional database engines.
- The accepted authorization and reference-environment decisions were added to the active design record.
- Created the proposed integrated design at `docs/architecture/m2-curation-design.md`, including the design context, component/data-flow map, domain and provenance model, authorization projection, resource budgets, storage and conversion boundaries, threats, acceptance matrix, and walking skeleton.
- Created proposed ADR-0005 through ADR-0010 and added them to the ADR index.
- Created implementation issues #53 through #61 with objective acceptance criteria, attached them to parent design issue #41, added them to the M2 milestone, and placed them in Project Todo. Together with existing issues #49 and #51, they form the ordered implementation backlog recorded in the design.
- Updated issue #41 with the ordered eleven-issue implementation backlog and its design-approval gate.
- `./mvnw -B verify` passed 91 tests plus formatting, OpenAPI compatibility, coverage, PMD, and SpotBugs checks.
- `git diff --check` passed.
- Frontend tests/build and Compose validation were not rerun because this change affects only Markdown design records and GitHub work records; the unrelated local Angular analytics preference was preserved unchanged.
- Committed the design as `7ba599f`, pushed the short-lived branch, and opened pull request #62 against protected `main` for owner approval.
- Pull request #62 passed its required checks and was squash-merged into protected `main` as `8438dbb`; this owner approval accepted ADR-0005 through ADR-0010 and unlocked the ordered implementation backlog.
