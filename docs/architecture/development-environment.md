# Yurlib Development Environment & AI Factory Design

**Project:** Yurlib  
**Status:** Active bootstrap baseline
**Version:** 0.8
**Last Updated:** 2026-09-28  
**Repository Path:** `docs/architecture/development-environment.md`

---

## 1. Purpose

This document defines the development environment and AI-assisted software delivery process for Yurlib.

Yurlib is developed as a **Codex-native engineering project**. Codex agents assist with planning, implementation, and review, while deterministic CI and human architectural approval remain the control points.

The repository is the source of truth for architecture, engineering rules, agent instructions, skills, tests, ADRs, and CI/CD configuration.

---

## 2. Initial Engineering Stack

| Area | Baseline |
|---|---|
| Source control | GitHub |
| Work management | GitHub Issues and Projects |
| Backend | Workstation-default JDK (Java 26 at bootstrap), Java 25 bytecode, Spring Boot 4.x |
| Build | Workstation-default Maven (Maven 3.9 at bootstrap) |
| Frontend | Angular using the workstation-default Node.js and npm toolchain |
| Database | PostgreSQL |
| Vector support | pgvector when needed |
| DB migrations | Flyway |
| Integration testing | Testcontainers |
| Architecture testing | ArchUnit |
| Observability | OpenTelemetry |
| CI/CD | GitHub Actions |
| Initial deployment | Docker / Docker Compose |
| Optional future deployment | Kubernetes + Helm or Kustomize |
| Infrastructure provisioning | Terraform only if later required |

Terraform is **not** part of the initial Yurlib baseline.

---

## 3. Repository Model

Yurlib uses a monorepo.

```text
yurlib/
├── services/
├── web/
├── contracts/
│   ├── openapi/
│   └── events/
├── libraries/
├── infrastructure/
│   └── docker/
├── docs/
│   ├── product/
│   ├── architecture/
│   ├── requirements/
│   └── adr/
├── .agents/
│   └── skills/
├── .github/
│   ├── instructions/
│   └── workflows/
├── AGENTS.md
├── README.md
├── pom.xml
└── docker-compose.yml
```

Future Kubernetes or Terraform directories are added only when those capabilities are adopted.

---

## 4. Git Workflow

Yurlib uses trunk-based development with short-lived branches.

```text
main
 ├── feature/123-description
 ├── bugfix/231-description
 └── chore/310-description
```

Rules:

- `main` should remain releasable;
- direct pushes to `main` are disabled;
- changes enter through pull requests;
- required CI checks must pass;
- default merge strategy is squash merge;
- GitHub issue numbers should be referenced in branches, PRs, and commits where practical.

---

## 5. GitHub Issues and Projects Workflow

Initial states:

```text
Backlog
   ↓
Ready for Design
   ↓
Design
   ↓
Ready for Implementation
   ↓
In Progress
   ↓
AI Review
   ↓
Human Review
   ↓
Done
```

Terminal alternatives:

```text
Cancelled
Duplicate
Deferred
```

GitHub Issues store actionable work. GitHub Projects organizes that work across the workflow. The repository stores code, design documents, ADRs, and pull-request history.

An issue derived from a design should reference the relevant repository document and section. Pull requests should link or close their issue where practical.

---

## 6. AI Factory

The initial AI factory uses **Codex only**.

```text
                         YOU
                Product Owner / Architect
                         │
                         ▼
                Planner / Architect
                    Codex Agent
                         │
                         ▼
                   Implementer
                    Codex Agent
                         │
                         ▼
                 Deterministic CI
                         │
                         ▼
                  Code Reviewer
                    Codex Agent
                         │
                 ┌───────┴────────┐
                 ▼                ▼
           Security Review    optional
             Codex Agent      specialist
                 │
                 └───────┬────────┘
                         ▼
                         YOU
                         │
                         ▼
                       Merge
```

The factory is intentionally small at first.

Initial roles:

1. Planner / Architect
2. Implementer
3. Code Reviewer
4. Security Reviewer

Additional specialist roles are introduced only when they provide clear value.

---

## 7. Codex Role Responsibilities

### 7.1 Planner / Architect

Primary responsibility: convert a ready GitHub issue into an implementation plan.

The planner:

- reads the GitHub issue and linked project context;
- reads linked designs and ADRs;
- inspects affected code;
- identifies affected components;
- identifies API, event, database, security, and observability implications;
- identifies required tests;
- proposes task decomposition when useful;
- flags unresolved architectural decisions.

The planner normally does not write production code.

### 7.2 Implementer

Primary responsibility: own the change from implementation through passing local verification.

The implementer:

- works in an isolated branch/worktree;
- follows repository architecture and instructions;
- implements production code;
- writes or updates tests;
- updates contracts and documentation where required;
- runs relevant verification before creating or updating the PR.

One implementation agent normally owns one change boundary.

Parallel implementation agents are used only when work can be cleanly separated by component or service.

### 7.3 Code Reviewer

The reviewer should use a separate Codex context from the implementer.

Review areas:

- requirement coverage;
- correctness;
- architecture;
- transactions and concurrency;
- API/event compatibility;
- error handling;
- database behavior;
- observability;
- unnecessary complexity;
- missing or weak tests.

The reviewer does not replace CI or human approval.

### 7.4 Security Reviewer

Used for security-relevant changes and for areas that process untrusted content.

Yurlib-specific focus:

- EPUB/ZIP handling;
- FB2/XML parsing;
- path traversal;
- unsafe filenames;
- command injection into conversion tools;
- remote library connectors;
- downloaded content;
- SSRF;
- secrets;
- container permissions;
- resource exhaustion.

The security reviewer is read-oriented and does not merge or deploy.

---

## 8. Future Specialist Roles

The following are deferred until needed:

```text
Java Specialist
Angular Specialist
Database Specialist
Infrastructure Specialist
Integration Agent
Test Reviewer
```

A specialist role is justified when separate context, tools, or ownership materially improve quality.

Do not route every task through every agent.

Typical small feature:

```text
GitHub Issue
 → Planner
 → Implementer
 → CI
 → Code Reviewer
 → You
```

Larger feature:

```text
GitHub Issue
 → Planner
 → multiple Implementers
 → Integration Agent
 → CI
 → Code Review / Security Review
 → You
```

---

## 9. Skills, Agents, MCP, and CI

These are separate concepts.

```text
Codex role
    = responsibility

Skill
    = repeatable procedure

MCP
    = access to external systems

CI
    = deterministic enforcement
```

Example:

```text
Implementer
├── skills
│   ├── java-spring-best-practices
│   ├── spring-boot-testing
│   └── architecture-decision
├── MCP
│   └── GitHub Issues, Projects, and pull requests
└── tools
    ├── git
    ├── Maven
    └── Docker
```

Project skills live under:

```text
.agents/skills/
```

The reviewed frontend skill set includes:

| Skill | Purpose | Reviewed source |
|---|---|---|
| `angular-developer` | Angular 22+ architecture, implementation, accessibility, routing, forms, signals, and testing guidance | `angular/skills` at `75005911fc668124af3fa2044f4f563e637fddd8` |
| `frontend-skill` | Visual direction, interface hierarchy, responsive composition, imagery, and motion guidance | `openai/plugins`, branch `update-build-web-apps`, at `31cb4d5e9b77e234da702050103e8a9d36ddfce0` |

Both skills are project-local dependencies under `.agents/skills/`. Their generic guidance remains subordinate to this document, `AGENTS.md`, accepted ADRs, and the repository's Angular architecture. In particular, `frontend-skill` must not introduce React or Framer Motion into Yurlib unless a separately approved architecture decision changes the frontend stack.

Skill installation and review policy is defined in:

```text
docs/architecture/agent-skills.md
```

---

## 10. Agent Permissions

Follow least privilege.

### Planner

```text
GitHub           issues / projects / repository read
Repository       read
Docs / ADRs      read
```

### Implementer

```text
GitHub           issues / projects read; branch / PR write
Repository       assigned worktree read/write
Build/test       execute
Docker           local execution as required
```

### Reviewer

```text
GitHub           issues / projects / repository read; PR comment
Repository       read
CI results       read
```

Codex agents do not receive unrestricted direct production access.

Production changes occur through the delivery pipeline.

---

## 11. Worktree Model

Parallel Codex agents use isolated Git worktrees.

Example:

```bash
git worktree add ../yurlib-101 feature/101-ingestion
git worktree add ../yurlib-102 feature/102-ui
```

Default rule:

> One active writer per file.

Good parallel split:

```text
Agent A → services/yurlib-server/src/main/java/**/ingestion/**
Agent B → web/yurlib-web/**
Agent C → infrastructure/**
```

Avoid splitting one small class or tightly coupled change across multiple agents.

---

## 12. Repository Instructions

Always-on project rules belong in:

```text
AGENTS.md
```

Path-specific instructions belong in:

```text
.github/instructions/
```

Project skills belong in:

```text
.agents/skills/
```

Architecture decisions belong in:

```text
docs/adr/
```

Where a rule can be enforced mechanically, prefer CI or architecture tests over prose.

---

## 13. Architectural Change Rule

An implementation agent must not silently introduce:

- a new database engine;
- a new message broker;
- a new service;
- a distributed cache;
- a search engine;
- a workflow engine;
- a service mesh;
- a major framework;
- a new persistent infrastructure component.

If needed, the agent proposes an ADR covering:

- problem;
- proposed solution;
- alternatives;
- operational cost;
- security implications;
- migration impact;
- why current infrastructure is insufficient.

Human architectural approval is required.

---

## 14. Backend Architecture Rules

Backend services should use clean/hexagonal boundaries where practical.

Typical structure:

```text
domain
application
api
infrastructure
```

Rules:

- domain code should avoid Spring dependencies where practical;
- controllers do not contain core business logic;
- persistence entities are not automatically API/domain models;
- services do not access another service's database directly;
- service boundaries use explicit APIs/events.

Architecture rules should be enforced with ArchUnit where practical.

---

## 15. Contracts

REST contracts live under:

```text
contracts/openapi/
```

Event contracts live under:

```text
contracts/events/
```

Breaking contract changes require explicit review.

Event consumers must be idempotent.

Events should carry standard metadata such as:

```text
eventId
eventType
version
timestamp
correlationId
causationId
producer
payload
```

Use the transactional outbox pattern where reliable database-to-event publication is required.

---

## 16. Database Rules

Initial persistence uses PostgreSQL.

Rules:

- schema changes use Flyway;
- released migrations are immutable;
- service-owned data remains service-owned;
- important invariants should use database constraints where appropriate;
- repository behavior should be tested against PostgreSQL with Testcontainers;
- pgvector is introduced only for features that need vector search/storage.

---

## 17. Testing Strategy

Use the smallest test that provides meaningful confidence.

```text
Unit tests
    ↓
Component tests
    ↓
Integration tests
    ↓
Contract tests
    ↓
Small E2E suite
```

Requirements:

- business logic receives unit coverage;
- repositories use real PostgreSQL integration tests where appropriate;
- bug fixes include regression tests when feasible;
- key service boundaries receive component/contract coverage;
- tests should verify requirements, not merely reproduce implementation logic.

Mutation testing may be added later for critical domain logic.

---

## 18. CI Quality Gate

Primary backend verification using the workstation Maven installation:

```bash
mvn verify
```

Target PR pipeline:

```text
compile
  ↓
format/style
  ↓
unit tests
  ↓
PMD
  ↓
SpotBugs
  ↓
ArchUnit
  ↓
coverage
  ↓
integration tests
  ↓
contract validation
  ↓
dependency/security checks
  ↓
container build/scan where applicable
```

CI failures block merge when the failed check is required.

AI review occurs after enough deterministic checks have completed to provide useful signal.

---

## 19. Security Engineering

Security is part of normal implementation.

Initial controls include:

- static security analysis;
- dependency vulnerability scanning;
- secret scanning;
- container scanning;
- SBOM generation for releases.

Yurlib treats ebook files, external metadata, and downloaded assets as untrusted input.

Third-party Agent Skills are also treated as untrusted until reviewed according to:

```text
docs/architecture/agent-skills.md
```

---

## 20. Local Development and Deployment

Initial supported deployment:

```text
Application
    ↓
Docker images
    ↓
Docker Compose
    ↓
Local Linux / NAS-connected host
```

Docker Compose should provide required infrastructure such as PostgreSQL and observability dependencies.

Application services may run directly from the IDE/build tool or in containers.

Kubernetes is optional future functionality.

If Kubernetes is adopted:

```text
Existing Kubernetes cluster
        ↓
Helm or Kustomize
        ↓
Yurlib
```

Terraform is introduced only if Yurlib later needs to provision infrastructure itself.

---

## 21. Observability

Production-relevant behavior should provide appropriate:

- structured logs;
- metrics;
- traces;
- health checks;
- readiness/liveness checks.

Correlation context should propagate through HTTP, events, background jobs, conversion jobs, and external connector calls.

Useful business metrics include:

```text
library.files.discovered
library.files.ingested
library.files.failed
conversion.jobs.completed
conversion.jobs.failed
connector.requests
connector.failures
ai.requests
```

---

## 22. Definition of Ready

A task is ready for implementation when it contains enough information to avoid inventing requirements.

Normally include:

- problem and desired outcome;
- acceptance criteria;
- relevant design/ADR references;
- affected subsystem;
- contract/database/security implications where applicable;
- explicit out-of-scope items where needed.

Architecturally significant work requires design or ADR input before implementation.

---

## 23. Definition of Done

A task is done when applicable criteria are satisfied:

- acceptance criteria met;
- tests added and passing;
- static/architecture/security checks pass;
- contracts and migrations updated;
- relevant observability added;
- documentation/ADR updated where required;
- independent Codex review completed where required;
- human review completed;
- no unapproved architectural dependencies introduced.

---

## 24. Initial Bootstrap Sequence

```text
1. Product concept                         DONE
2. GitHub repository and main protection   DONE
3. Development environment design          DONE
4. Record work-management decision         DONE — ADR-0001
5. Configure GitHub Issues                  DONE
6. Configure GitHub Project                 DONE
7. Agent skills review/install              DONE
8. Convert engineering design into issues   DONE
9. Create repository agent instructions     DONE
10. Build CI foundation                     DONE
11. Implement first product vertical slice  NEXT
```

Do not generate substantial product code before the engineering baseline is usable.

---

## 25. Initial Engineering Project

Configured GitHub Project and parent issue:

```text
Project — Yurlib Engineering — https://github.com/users/yurtools/projects/1
Parent issue — Engineering Environment — https://github.com/yurtools/yurlib/issues/11
```

Initial work should cover:

```text
Repository structure
Workstation-default Java / Maven
Spring Boot service template
Angular foundation
Docker Compose environment
PostgreSQL
Testcontainers
Formatting / PMD / SpotBugs / JaCoCo
ArchUnit
GitHub Actions
Dependency and secret scanning
AGENTS.md
Codex Planner role
Codex Implementer role
Codex Reviewer role
Codex Security Reviewer role
Agent skills installation
Worktree workflow
ADR template/process
OpenAPI/event standards
Observability baseline
Definition of Ready / Done
Branch protection
```

Messaging infrastructure should be selected only when an actual product requirement justifies it.

---

## 26. First Product Vertical Slice

The first product feature should validate the complete factory.

> Configure one library root and display discovered EPUB, FB2, and MOBI books in a persistent catalog.

The approved implementation design is recorded in:

```text
docs/architecture/local-library-vertical-slice.md
contracts/openapi/yurlib-v1.yaml
docs/adr/0003-first-local-library-slice-boundaries.md
```

Expected behavior:

- library root can be configured;
- supported files are discovered;
- metadata is extracted;
- repeated scans are idempotent;
- unchanged files are not unnecessarily reprocessed;
- failures do not terminate the scan;
- failures are visible;
- catalog data persists;
- progress is visible;
- original assets can be downloaded;
- restart does not require complete re-import.

This feature should exercise:

```text
GitHub Issue / Project
Planner
Implementer
Git worktree
CI
Testcontainers
ArchUnit
Code Review
Security Review where applicable
Human Review
Docker Compose
Observability
```

---

## 27. Current Decisions

| Area | Decision |
|---|---|
| AI runtime | Codex only |
| Initial AI roles | Planner, Implementer, Code Reviewer, Security Reviewer |
| Agent procedures | `.agents/skills/` |
| Agent policy | `AGENTS.md` |
| Agent external systems | MCP |
| Repository | Monorepo |
| Work management | GitHub Issues and Projects (ADR-0001) |
| Branching | Trunk-based |
| Merge | Squash |
| Agent isolation | Git worktrees |
| Backend | Workstation-default JDK (Java 26 at bootstrap), Java 25 bytecode + Spring Boot 4.x |
| Build | Workstation-default Maven (Maven 3.9 at bootstrap) |
| Frontend | Angular |
| Database | PostgreSQL |
| Project license | GPL-3.0-only |
| Initial deployment | Docker Compose |
| Kubernetes | Deferred / optional |
| Terraform | Deferred; infrastructure provisioning only |
| Human authority | Required for architectural changes |

---

## 28. Open Decisions

Deferred until needed:

- message broker;
- authentication model;
- Kubernetes support;
- cloud deployment;
- dedicated search engine;
- automated agent execution from GitHub events.

These should be decided from actual requirements rather than preinstalled infrastructure.

---

## 29. Change Control

Changes to this engineering model should be made through:

1. an update to this document;
2. an accepted ADR; or
3. an approved architecture task that updates the relevant documentation.

Implementation behavior that contradicts the documented process should not become an informal standard by accident.
