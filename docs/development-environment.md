# Yurlib Development Environment & AI-Native SDLC Design

**Project:** Yurlib  
**Document:** Development Environment & AI-Native SDLC Design  
**Status:** Draft for approval  
**Version:** 0.2  
**Last Updated:** 2026-09-23  
**Repository Path:** `docs/architecture/development-environment.md`

---

## 1. Purpose

This document defines the software development environment, engineering workflow, quality gates, AI-agent operating model, repository conventions, testing strategy, review process, and delivery standards for Yurlib.

Yurlib is intentionally being developed as both:

1. a useful self-hosted electronic library management platform; and
2. a reference implementation for modern AI-assisted software engineering.

The goal is not to allow AI agents to generate code without control. The goal is to make architecture, requirements, implementation, testing, review, and delivery explicit enough that human developers and AI agents can work against the same engineering system.

This document is the authoritative design for the Yurlib development process unless superseded by an approved Architecture Decision Record (ADR) or a later version of this document.

---

# 2. Engineering Objectives

The Yurlib development environment must support the following objectives:

- maintain strong architectural control while using AI agents extensively;
- make engineering rules discoverable from the repository;
- allow multiple implementation agents to work concurrently without interfering with each other;
- keep each change traceable from requirement to design, task, code, tests, review, and release;
- make automated validation stronger than informal convention;
- minimize manual review of formatting, boilerplate, and obvious mechanical issues;
- keep human review focused on domain semantics, architecture, risk, and product correctness;
- keep the main branch releasable;
- make local development reproducible;
- support deterministic CI/CD;
- support isolated agent workspaces;
- support independent AI review;
- prevent agents from silently introducing major architectural dependencies;
- allow development tooling to evolve without destabilizing the product.

---

# 3. Guiding Principles

## 3.1 Repository as Engineering Source of Truth

Important engineering rules must live in the repository.

These include:

- architecture principles;
- coding standards;
- testing requirements;
- security requirements;
- API standards;
- event standards;
- AI-agent instructions;
- ADRs;
- Definition of Ready;
- Definition of Done;
- CI configuration.

Chat history, IDE settings, and individual developer knowledge are not authoritative substitutes.

---

## 3.2 Architecture Before Implementation

Significant implementation work should begin from an explicit requirement and, where appropriate, a design.

Agents must not infer major architectural decisions from incomplete tickets.

When a task requires a new database, message broker, framework, service, infrastructure component, or major dependency, the agent must propose the change rather than silently introduce it.

---

## 3.3 Deterministic Controls Before AI Judgment

AI review supplements deterministic engineering controls.

Where a rule can be enforced mechanically, it should be.

Examples:

- formatting;
- compilation;
- dependency rules;
- unit tests;
- architecture rules;
- API schema validation;
- security scanning;
- dependency scanning;
- migration validation;
- container scanning.

AI review is used for higher-level issues that are difficult to express as deterministic checks.

---

## 3.4 One Owner Per Change Boundary

A single implementation agent normally owns a task from implementation through passing relevant verification.

Parallel implementation agents may be used when work can be cleanly divided along architectural boundaries.

Agents should not concurrently modify the same files unless an explicit integration step is planned.

---

## 3.5 Human Architectural Authority

AI agents may:

- propose designs;
- implement approved work;
- write tests;
- review code;
- identify risks;
- suggest ADRs.

AI agents may not independently approve major architectural changes.

Final authority for:

- service boundaries;
- core domain model;
- security model;
- infrastructure strategy;
- persistence technology;
- messaging technology;
- new frameworks;
- production deployment policy;

remains with the project owner/architect.

---

# 4. Core Engineering Platform

The initial engineering platform consists of:

- GitHub for source control, pull requests, CI integration, and repository governance;
- Plane for work management;
- Docker / Docker Compose for the initial local deployment model and development services;
- Java 25 LTS for backend services;
- Spring Boot 4.x for backend application services;
- Maven with Maven Wrapper for builds;
- Angular for the web application;
- PostgreSQL for relational persistence;
- pgvector where vector storage is required;
- Testcontainers for integration testing;
- OpenTelemetry for observability instrumentation;
- GitHub Actions for CI/CD.

Optional future deployment and infrastructure tooling:

- Kubernetes may be supported later as an additional deployment target;
- Helm or Kustomize may be used to deploy Yurlib into an existing Kubernetes cluster;
- Terraform may be introduced only if Yurlib needs to provision infrastructure rather than merely deploy application workloads.

Exact patch versions should be pinned in repository configuration and upgraded deliberately.

---

# 5. Source Control

## 5.1 Repository Model

Yurlib will initially use a monorepo.

Expected structure:

```text
yurlib/
├── services/
│   ├── catalog-service/
│   ├── ingestion-service/
│   ├── conversion-service/
│   └── ai-service/
│
├── web/
│   └── yurlib-web/
│
├── contracts/
│   ├── openapi/
│   └── events/
│
├── libraries/
│   ├── observability/
│   ├── event-support/
│   └── test-support/
│
├── infrastructure/
│   ├── docker/
│   ├── kubernetes/     # future, if Kubernetes becomes a supported target
│   └── terraform/      # future, only if infrastructure provisioning is required
│
├── docs/
│   ├── product/
│   ├── architecture/
│   ├── requirements/
│   └── adr/
│
├── .github/
│   ├── agents/
│   ├── instructions/
│   ├── workflows/
│   └── prompts/
│
├── AGENTS.md
├── README.md
├── pom.xml
├── mvnw
└── docker-compose.yml
```

The structure may evolve through ADRs as implementation experience is gained.

---

## 5.2 Branching Model

Yurlib will use trunk-based development with short-lived branches.

Primary branch:

```text
main
```

Typical branch names:

```text
feature/ELIB-123-add-library-root
bugfix/ELIB-231-fix-duplicate-ingestion
chore/ELIB-310-upgrade-spring
docs/ELIB-420-document-event-contract
```

Long-lived development branches are discouraged.

---

## 5.3 Main Branch Policy

`main` should always be releasable.

Direct pushes to `main` should be disabled once repository protections are configured.

Changes should normally enter `main` through pull requests.

Required checks must pass before merge.

---

## 5.4 Merge Strategy

Default merge strategy:

```text
Squash and merge
```

The resulting commit should reference the Plane work item where applicable.

Example:

```text
ELIB-123 Add library root registration
```

---

# 6. Plane Work Management

## 6.1 Purpose

Plane is the primary system for planning and tracking engineering work.

GitHub Issues may be used for public discussion and external reports, but implementation work should ultimately be represented in Plane.

---

## 6.2 Work Item Types

Initial work item types:

- Epic
- Feature
- Story
- Bug
- Architecture
- Security
- Technical Debt
- Spike

---

## 6.3 Workflow

Initial workflow:

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

Additional terminal states:

```text
Cancelled
Duplicate
Deferred
```

---

## 6.4 Work Item Traceability

A Plane item derived from a design document should reference:

- source document;
- relevant section;
- related ADRs;
- acceptance criteria;
- affected components.

Example:

```text
ELIB-010 — Create GitHub Actions PR pipeline

Source:
docs/architecture/development-environment.md

Relevant section:
§ 14 Continuous Integration

Acceptance Criteria:
- Build executes on every pull request.
- Unit tests execute.
- Integration tests execute.
- Static analysis executes.
- Architecture tests execute.
- Dependency checks execute.
- Required failures block merge.
```

---

# 7. Definition of Ready

A work item is Ready for Implementation when the implementation agent has enough information to begin without inventing requirements.

A normal implementation ticket should include:

- problem statement;
- desired outcome;
- acceptance criteria;
- affected subsystem or likely ownership boundary;
- relevant design references;
- API or event contract changes where applicable;
- persistence implications where applicable;
- security implications where applicable;
- operational or observability expectations;
- explicit out-of-scope items where ambiguity is likely.

Not every minor task requires a formal design document.

Architecturally significant tasks do.

---

# 8. Definition of Done

A work item is Done when all applicable requirements below are satisfied.

## 8.1 Functional

- acceptance criteria are met;
- failure behavior is implemented;
- edge cases identified during implementation are handled or explicitly documented.

## 8.2 Testing

- relevant unit tests exist;
- relevant integration tests exist;
- regression tests exist for bug fixes;
- test suite passes;
- new tests meaningfully verify behavior rather than simply mirror implementation.

## 8.3 Quality

- code compiles;
- formatting checks pass;
- static analysis passes;
- architecture tests pass;
- no unapproved dependency or architecture changes are introduced.

## 8.4 Security

- security-sensitive input is validated;
- no secrets are introduced into the repository;
- new dependencies pass required checks;
- appropriate security tests exist where applicable.

## 8.5 Operability

Where applicable:

- useful logs exist;
- metrics exist;
- traces are preserved;
- errors are diagnosable;
- health/readiness behavior is correct.

## 8.6 Documentation

- API/event contracts are updated;
- architectural documentation is updated if behavior or boundaries changed;
- ADR is created or updated for significant architectural decisions.

## 8.7 Review

- deterministic CI passes;
- independent AI review is completed where required;
- required human review is completed;
- review findings are resolved or explicitly dispositioned.

---

# 9. AI Agent Operating Model

## 9.1 Initial Agent Roles

Yurlib will initially define the following logical agents:

```text
Architect / Planner Agent
Java Implementation Agent
Angular Implementation Agent
Database Specialist Agent
Infrastructure Specialist Agent
Integration Agent
Code Review Agent
Security Review Agent
Test Review Agent
```

Not every agent is required for every task.

---

# 10. Architect / Planner Agent

The architect/planner agent normally does not implement production code.

Responsibilities:

- read Plane work items;
- inspect relevant product and architecture documentation;
- inspect relevant source code;
- identify affected components;
- identify API/event changes;
- identify persistence changes;
- identify security concerns;
- identify test requirements;
- produce an implementation plan;
- decompose large work into independent tasks where useful;
- identify unresolved architectural decisions.

The planner should prefer existing architecture over introducing new infrastructure.

---

# 11. Implementation Agents

## 11.1 Java Implementation Agent

Primary responsibilities:

- backend implementation;
- domain logic;
- application logic;
- REST APIs;
- event producers/consumers;
- persistence adapters;
- tests;
- relevant documentation updates.

Before completion, the agent must run relevant build verification.

---

## 11.2 Angular Implementation Agent

Primary responsibilities:

- UI components;
- client-side services;
- API integration;
- UI tests;
- accessibility requirements;
- user-facing error handling;
- relevant documentation updates.

---

## 11.3 Database Specialist Agent

Used when a change has material database implications.

Responsibilities may include:

- Flyway migrations;
- constraints;
- indexes;
- query plans;
- locking behavior;
- transaction semantics;
- migration safety;
- backward-compatible schema evolution.

This agent may review rather than directly implement small database changes.

---

## 11.4 Infrastructure Specialist Agent

Used for:

- Docker;
- Docker Compose;
- GitHub Actions;
- container security;
- deployment configuration;
- observability infrastructure;
- Kubernetes/Helm if Kubernetes support is introduced;
- Terraform only if infrastructure provisioning is later adopted.

---

# 12. Integration Agent

The integration agent is used when multiple implementation agents contribute to one larger feature.

Responsibilities:

- combine completed changes;
- resolve contract mismatches;
- verify shared configuration;
- validate event compatibility;
- validate API compatibility;
- validate database migrations;
- run the full applicable test suite;
- ensure the combined result satisfies the original requirement.

The integration agent should not redesign the feature unless integration exposes a design defect.

---

# 13. Review Agents

## 13.1 Code Review Agent

The code review agent should be independent from the implementation session where practical.

Primary review areas:

- correctness;
- requirement coverage;
- domain logic;
- concurrency;
- transactions;
- exception handling;
- API compatibility;
- event compatibility;
- N+1 database behavior;
- resource leaks;
- architecture violations;
- observability gaps;
- unnecessary complexity;
- insufficient tests.

The review agent should not approve code solely because tests pass.

---

## 13.2 Security Review Agent

Review areas include:

- authorization;
- authentication;
- path traversal;
- archive/ZIP bombs;
- XML external entity handling;
- malicious EPUB/HTML content;
- unsafe file paths;
- command injection into conversion tools;
- SSRF;
- secrets exposure;
- credential handling;
- container privileges;
- external connector input;
- denial-of-service risks;
- unsafe deserialization;
- dependency vulnerabilities.

Yurlib processes untrusted ebook files and potentially untrusted remote content, so security review is a first-class part of the development model.

---

## 13.3 Test Review Agent

The test review agent focuses on whether tests provide meaningful evidence.

It should look for:

- tests that merely duplicate implementation logic;
- missing negative cases;
- missing concurrency/idempotency cases;
- brittle over-mocking;
- missing database integration coverage;
- missing contract coverage;
- bug fixes without regression tests.

---

# 14. Agent Workspaces and Git Worktrees

Parallel agents should use isolated Git worktrees.

Example:

```bash
git worktree add ../yurlib-ELIB-101 feature/ELIB-101-ingestion
git worktree add ../yurlib-ELIB-102 feature/ELIB-102-catalog-ui
git worktree add ../yurlib-ELIB-103 feature/ELIB-103-infra
```

Each agent receives:

- one worktree;
- one branch;
- one primary work item;
- explicit ownership scope.

---

## 14.1 One Writer Per File Rule

As a default:

> One active implementation agent should own a file at a time.

Parallel work should be divided along component or architectural boundaries.

Good split:

```text
Agent A → services/ingestion-service/**
Agent B → web/yurlib-web/**
Agent C → infrastructure/**
```

Poor split:

```text
Agent A → controller methods in FileA.java
Agent B → service methods in FileA.java
```

---

# 15. Agent Permissions

Agent permissions should follow least privilege.

## 15.1 Implementation Agent

Typical access:

```text
Git repository      read/write to assigned branch
Plane               read assigned work
Documentation       read
RAG/context         read
Build/test tools    execute
Local containers    execute as required
```

## 15.2 Review Agent

Typical access:

```text
Git repository      read
Pull request        read/comment
Plane               read
Documentation       read
CI results          read
```

Review agents normally do not directly merge or deploy.

## 15.3 Production Access

AI agents should not receive unrestricted direct production access.

Production changes should occur through approved delivery pipelines.

---

# 16. Repository-Level AI Instructions

The repository should contain a root:

```text
AGENTS.md
```

This file provides concise global rules understood by coding agents.

It should cover:

- architecture direction;
- dependency rules;
- testing expectations;
- security constraints;
- migration policy;
- contract policy;
- required verification commands;
- rules for proposing architecture changes.

Path-specific instructions may live under:

```text
.github/instructions/
```

Examples:

```text
java.instructions.md
testing.instructions.md
database.instructions.md
security.instructions.md
api.instructions.md
events.instructions.md
angular.instructions.md
```

Agent definitions may live under:

```text
.github/agents/
```

---

# 17. Architectural Change Rule

An implementation agent must not silently introduce:

- a new database engine;
- a new message broker;
- a new service;
- a new distributed cache;
- a new search engine;
- a major framework;
- a new cloud platform;
- a workflow engine;
- a service mesh;
- a new persistent infrastructure component.

If such a change appears necessary, the agent must propose an ADR.

The proposal should state:

- problem;
- current limitation;
- proposed technology;
- alternatives considered;
- operational cost;
- development cost;
- security implications;
- migration implications;
- why existing infrastructure is insufficient.

The project owner/architect decides whether to proceed.

---

# 18. Architecture Decision Records

ADRs are stored under:

```text
docs/adr/
```

Naming convention:

```text
0001-use-java-25.md
0002-use-spring-boot.md
0003-use-postgresql.md
0004-service-boundaries.md
```

ADR structure:

```text
Title
Status
Context
Decision
Alternatives Considered
Consequences
```

Typical statuses:

```text
Proposed
Accepted
Superseded
Rejected
Deprecated
```

---

# 19. Backend Architecture Standards

Backend services should use clean/hexagonal architecture principles where practical.

Typical package structure:

```text
com.yurlib.<service>
├── domain
├── application
├── api
└── infrastructure
```

Dependency direction:

```text
API ───────────────┐
                   ▼
             Application
                   │
                   ▼
                 Domain
                   ▲
                   │
Infrastructure ────┘
```

The domain layer should avoid direct dependencies on Spring where practical.

Controllers must not contain core business logic.

Persistence entities must not become the public domain/API model by default.

Services must not directly access another service's database.

---

# 20. Contract Standards

## 20.1 REST

Service boundaries should use explicit OpenAPI contracts where appropriate.

Contracts live under:

```text
contracts/openapi/
```

Breaking API changes require explicit review.

---

## 20.2 Events

Event schemas live under:

```text
contracts/events/
```

Events should include standard metadata such as:

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

Consumers must be designed for idempotency.

Distributed transactions should not be introduced as a default integration mechanism.

For database-to-event publication, the transactional outbox pattern should be preferred where reliable publication is required.

---

# 21. Database Standards

Initial relational persistence uses PostgreSQL.

Database schema changes use Flyway migrations.

Rules:

- migrations are immutable after release;
- service-owned data remains service-owned;
- schema changes should be backward compatible where practical;
- indexes must be justified by access patterns;
- database constraints should enforce important invariants where appropriate;
- tests involving repository behavior should use real PostgreSQL through Testcontainers rather than only mocks.

Vector data may use pgvector where justified.

---

# 22. Build System

Backend builds use Maven through the repository wrapper:

```bash
./mvnw
```

Developers and agents should not depend on a globally installed Maven version.

The repository should define common dependency/plugin management at the root where appropriate.

Primary verification command:

```bash
./mvnw verify
```

Individual modules may support narrower verification commands for development speed.

---

# 23. Static Analysis and Code Quality

The initial backend quality toolchain should include:

- compiler warnings configured appropriately;
- formatting enforcement;
- PMD;
- SpotBugs;
- JaCoCo;
- ArchUnit;
- dependency checks;
- security scanning.

Additional tools may be added when they provide measurable value.

Static analysis rules should avoid excessive noise.

The objective is actionable quality enforcement, not maximizing the number of warnings.

---

# 24. Testing Strategy

## 24.1 Unit Tests

Used for:

- domain behavior;
- algorithms;
- matching logic;
- deterministic transformation logic;
- validation.

Unit tests should be fast and independent of external infrastructure.

---

## 24.2 Integration Tests

Use Testcontainers for real infrastructure where practical.

Examples:

- PostgreSQL;
- message broker;
- object/file services where applicable.

Repository behavior should generally be tested against a real PostgreSQL instance rather than only mocked repositories.

---

## 24.3 Component Tests

Component tests exercise a service boundary with most of the service running.

These are appropriate for REST endpoints, ingestion workflows, and event handling.

---

## 24.4 Contract Tests

Contract tests validate:

- OpenAPI compatibility;
- producer/consumer event compatibility;
- schema changes.

---

## 24.5 End-to-End Tests

A smaller suite should validate key user journeys.

Examples:

```text
Mount library root
→ scan files
→ extract metadata
→ persist catalog
→ display books in UI
```

E2E tests should be fewer than lower-level tests due to execution cost and brittleness.

---

## 24.6 Regression Tests

Every bug fix should include a regression test when technically feasible.

---

## 24.7 Mutation Testing

Mutation testing may be added for critical domain logic.

It need not run on every pull request initially.

It may run:

- nightly;
- on selected packages;
- on high-risk changes.

---

# 25. Architecture Enforcement

Architecture rules should be implemented with ArchUnit where possible.

Examples:

- API packages must not depend directly on persistence implementation packages;
- domain packages must not depend on Spring;
- services must not reference another service's internal implementation packages;
- forbidden dependency patterns fail CI.

Architecture documents describe intended rules.

Architecture tests enforce them.

---

# 26. Security Engineering

Security begins in the development environment rather than being added at release time.

Initial controls should include:

- CodeQL or equivalent static security analysis;
- dependency vulnerability scanning;
- secret scanning;
- container image scanning;
- infrastructure-as-code scanning where applicable;
- SBOM generation for release artifacts.

Yurlib-specific threat areas include:

- malicious EPUB/ZIP content;
- FB2 XML attacks;
- path traversal;
- unsafe filenames;
- command injection;
- conversion tool invocation;
- external library connectors;
- remote content;
- resource exhaustion.

---

# 27. Secrets and Credentials

Secrets must not be committed to source control.

Local development should use environment-specific secret mechanisms.

GitHub Actions should prefer short-lived federated credentials for cloud access rather than persistent access keys.

Connector credentials must be stored separately from ordinary catalog metadata.

---

# 28. Local Development Environment

A new developer or agent should be able to start the local environment with minimal manual setup.

Docker Compose should initially provide required infrastructure such as:

```text
PostgreSQL
pgvector support
message broker if selected
OpenTelemetry Collector
observability services where practical
```

Application services may run either:

- directly from the IDE/build tool; or
- in containers.

Both modes should be documented.

## 28.1 Initial Deployment Model

The initial supported deployment model is:

```text
Application build
      ↓
Docker images
      ↓
Docker Compose
      ↓
Local server / NAS-connected host
```

Docker Compose is therefore part of the initial Yurlib baseline.

Kubernetes is optional future functionality, not an initial requirement.

If Kubernetes support is added, the expected application deployment model is:

```text
Existing Kubernetes cluster
        ↓
Helm or Kustomize
        ↓
Yurlib workloads
```

Supporting Kubernetes does **not** imply that Terraform is required.

Terraform becomes relevant only if Yurlib later needs to provision infrastructure such as:

- Kubernetes clusters;
- cloud networking;
- managed databases;
- DNS;
- load balancers;
- cloud IAM;
- object storage;
- other persistent cloud resources.

---

# 29. Container Standards

Application containers should:

- run as non-root where practical;
- use minimal base images;
- use explicit versions;
- expose health endpoints;
- not contain development credentials;
- mount only required filesystem paths;
- use read-only mounts for read-only library roots;
- separate application data from image contents.

---

# 30. Observability

Observability is part of the Definition of Done for production-relevant behavior.

Each service should provide:

- structured logs;
- metrics;
- distributed traces;
- health endpoints;
- readiness checks;
- liveness checks.

Common correlation context should propagate across:

```text
HTTP
events
background jobs
conversion work
connector requests
```

Business-level metrics should be added where useful.

Examples:

```text
library.files.discovered
library.files.ingested
library.files.failed
conversion.jobs.completed
conversion.jobs.failed
connector.requests
connector.failures
ai.requests
ai.suggestions.accepted
```

---

# 31. Continuous Integration

Every pull request should run the applicable CI pipeline.

Target pipeline:

```text
checkout
   ↓
validate repository
   ↓
compile
   ↓
format/style checks
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
container build where applicable
   ↓
container/SBOM scan where applicable
```

Checks required for merge should be explicit in branch protection.

---

# 32. AI Review in Pull Requests

AI review occurs after deterministic checks have produced enough signal to make review useful.

The AI reviewer should receive:

- Plane requirement;
- relevant architecture/design documents;
- pull request diff;
- changed contracts;
- CI results;
- relevant surrounding code.

The AI reviewer should produce actionable findings rather than generic commentary.

Findings should include:

```text
severity
location
problem
reason
suggested remediation
```

---

# 33. Human Review

Human review should focus on matters where architectural and product judgment are most valuable:

- whether the feature solves the requirement;
- domain semantics;
- service boundaries;
- data ownership;
- consistency model;
- failure semantics;
- security-sensitive design;
- operational risk;
- unnecessary complexity;
- long-term maintainability.

The human reviewer should not spend significant time manually enforcing formatting or simple style rules that can be automated.

---

# 34. Continuous Delivery

Initial delivery should build immutable artifacts from `main`.

Conceptual flow:

```text
main
  ↓
build
  ↓
test
  ↓
container image
  ↓
SBOM
  ↓
artifact provenance
  ↓
development deployment
  ↓
smoke/integration validation
  ↓
promotion
```

The same built artifact should be promoted between environments where practical.

Production deployment must not depend on rebuilding the source independently.

---

# 35. Release and Versioning Strategy

Initial versioning may use semantic versioning once public releases begin.

Before the first stable release, versions may remain within:

```text
0.x.y
```

Release artifacts should be traceable to:

- Git commit;
- CI run;
- container digest;
- dependency/SBOM data.

Release policy will be refined before the first public binary/container release.

---

# 36. Dependency Management

Dependencies should be centralized where practical.

Rules:

- avoid duplicate libraries providing the same function;
- prefer mature, maintained libraries;
- new dependencies should solve a demonstrated problem;
- security-critical dependencies require additional scrutiny;
- avoid introducing large frameworks for small utility needs;
- versions should be controlled centrally where appropriate.

Automated dependency updates may be introduced after CI is stable.

---

# 37. Documentation Standards

Important documentation belongs in the repository.

Expected categories:

```text
docs/product/
docs/architecture/
docs/requirements/
docs/adr/
```

Documentation should describe current intended behavior rather than historical conversation.

Design documents should be updated when approved behavior changes.

---

# 38. Engineering Traceability

Yurlib should maintain the following conceptual chain:

```text
Product Concept
      ↓
Architecture / Design
      ↓
Plane Epic / Story / Task
      ↓
Implementation Plan
      ↓
Branch / Worktree
      ↓
Pull Request
      ↓
Tests / CI
      ↓
AI Review
      ↓
Human Review
      ↓
Merge
      ↓
Release Artifact
```

Each stage should reference the preceding source where practical.

---

# 39. Initial Development Bootstrap Sequence

The development environment will be established in the following order.

## Step 1 — Product Concept

Status:

```text
Completed
```

Authoritative document:

```text
docs/product/product-concept.md
```

---

## Step 2 — GitHub Repository

Create the public `yurlib` repository.

Initial contents:

```text
README.md
docs/
.gitignore
LICENSE when selected
```

Application scaffolding is not required yet.

---

## Step 3 — Local Plane Installation

Install Plane locally.

Create:

```text
Workspace: Yurlib
Project: Yurlib
```

Verify:

- workspace works;
- project works;
- API access works;
- MCP access works if selected;
- GitHub integration can be configured.

Avoid creating a large issue backlog before the development process is finalized.

---

## Step 4 — Development Environment Design

Create and approve this document:

```text
docs/architecture/development-environment.md
```

This document establishes the target engineering system.

---

## Step 5 — Configure Plane from the Design

Configure:

- work item types;
- statuses;
- labels;
- priorities;
- cycles/modules if useful;
- GitHub integration;
- agent/MCP access where appropriate.

---

## Step 6 — Convert the Design into Plane Tasks

The development environment document becomes the source for the Engineering Environment epic and its implementation work items.

The Plane backlog represents work required to reach the documented target state.

---

# 40. Initial Engineering Environment Epic

Suggested Plane epic:

```text
EPIC — Engineering Environment
```

Initial tasks should include at least:

```text
Initialize repository structure
Configure Java 25 toolchain
Configure Maven Wrapper
Create root Maven build
Create backend service template
Create Angular application foundation
Create Docker Compose development environment
Configure PostgreSQL
Select/configure messaging infrastructure if required
Configure Testcontainers
Configure formatting
Configure PMD
Configure SpotBugs
Configure JaCoCo
Create ArchUnit rules
Create GitHub Actions PR pipeline
Configure dependency scanning
Configure secret scanning
Configure container scanning
Create AGENTS.md
Create path-specific agent instructions
Define planner agent
Define Java implementation agent
Define Angular implementation agent
Define integration agent
Define code review agent
Define security review agent
Define worktree workflow
Create ADR template/process
Create OpenAPI standards
Create event standards
Create observability baseline
Create Definition of Ready
Create Definition of Done
Configure branch protection
Configure release artifact metadata
```

Tasks should be refined and prioritized before implementation.

---

# 41. First Product Vertical Slice

After the engineering platform is functional, the first product slice should validate the full development lifecycle.

Proposed first vertical slice:

> Configure one library root and display discovered EPUB, FB2, and MOBI books in a persistent catalog.

Expected behavior:

- library root can be configured;
- supported files are discovered;
- metadata is extracted;
- files are fingerprinted;
- repeated scans are idempotent;
- unchanged files are not unnecessarily reprocessed;
- failures do not terminate the overall scan;
- failed assets are visible;
- catalog data persists;
- scan progress is visible;
- original assets can be downloaded;
- container restart does not require a complete import.

This feature should exercise:

- Plane workflow;
- planning agent;
- implementation agent;
- CI;
- integration tests;
- architecture tests;
- AI review;
- human review;
- containerized local environment;
- observability.

---

# 42. Initial Decisions

The following are current baseline decisions for the project.

| Area | Decision |
|---|---|
| Repository | Monorepo |
| Source Control | GitHub |
| Work Management | Plane |
| Backend Language | Java 25 LTS |
| Backend Framework | Spring Boot 4.x |
| Build | Maven Wrapper |
| Frontend | Angular |
| Relational Database | PostgreSQL |
| Vector Extension | pgvector when needed |
| Database Migrations | Flyway |
| Testing | JUnit + Testcontainers |
| Architecture Testing | ArchUnit |
| Observability | OpenTelemetry |
| CI/CD | GitHub Actions |
| Local Deployment | Docker Compose |
| Kubernetes | Deferred / optional |
| Kubernetes Application Deployment | Helm or Kustomize if Kubernetes is adopted |
| Infrastructure Provisioning | Deferred; Terraform only if later required |
| Branching | Trunk-based, short-lived branches |
| Merge | Squash merge |
| Agent Isolation | Git worktrees |
| AI Review | Independent review agent where practical |
| Human Approval | Required for architectural changes |

---

# 43. Open Decisions

The following decisions are intentionally deferred.

## 43.1 Message Broker

Possible options may include:

- Kafka;
- Redpanda;
- RabbitMQ;
- another compatible transport.

Selection should be based on actual eventing requirements rather than predetermined preference.

---

## 43.2 Authentication Model

Authentication requirements depend on whether the initial deployment is:

- trusted local network only;
- single-user;
- multi-user;
- internet-exposed.

The security model must be decided before public remote access is supported.

---

## 43.3 Public License

A project license must be selected before the repository should be treated as open source.

---

## 43.4 Deployment Target

Initial development and the initial supported deployment model use Docker Compose.

Kubernetes is not an initial requirement. If it becomes a supported target, Yurlib should deploy into an existing cluster using Helm or Kustomize.

Cloud or on-premises infrastructure provisioning is a separate concern. Terraform should be introduced only if an approved architecture decision requires Yurlib to create or manage infrastructure resources.

---

## 43.5 Search Infrastructure

PostgreSQL should be used initially for relational and basic text search.

A dedicated search engine should only be introduced when measurements demonstrate a need.

---

# 44. Approval Criteria for This Document

This document is ready to become the basis of implementation when the project owner accepts:

- engineering platform;
- repository model;
- Plane workflow;
- agent roles;
- agent permission model;
- branching strategy;
- testing strategy;
- CI quality gates;
- architectural change policy;
- first bootstrap sequence.

After approval, the next step is to convert the actionable sections into Plane epics, stories, and tasks.

---

# 45. Change Control

Changes to this development model should occur through one of:

1. a revision to this document;
2. an accepted ADR;
3. an approved Plane architecture task resulting in documentation updates.

Implementation behavior that contradicts the documented engineering process should be treated as technical debt or corrected before it becomes an informal standard.
