# Yurlib

**Yurlib** is a self-hosted electronic library management platform for organizing, cataloging, enriching, converting, and serving large ebook collections.

It is designed for people who already have books stored on local disks or NAS devices and want a modern, searchable library without giving up control of their files.

Yurlib is also being developed as an **AI-native software engineering project**: architecture, requirements, implementation, testing, review, and delivery are designed to work well with modern coding agents while keeping important technical decisions under human control.

> **Project status:** Early design and bootstrap phase.  
> The product architecture and engineering environment are being defined before application implementation begins.

---

## Goals

Yurlib aims to provide:

- fast background ingestion of large ebook collections;
- support for EPUB, FB2, MOBI, and additional formats through extensions;
- clear separation between books, editions, and physical ebook files;
- metadata extraction and normalization;
- author and contributor alias management;
- duplicate and alternate-edition detection;
- tags and collections;
- search and browsing through a web interface;
- ebook download and format conversion;
- read-only or managed NAS-backed libraries;
- external electronic-library connectors;
- AI-assisted catalog cleanup and metadata enrichment;
- container-based self-hosted deployment.

The system should remain fully useful without AI or external library services.

---

## Core Concepts

Yurlib does not treat every ebook file as an independent book.

A single work may have multiple editions, and each edition may have multiple assets:

```text
Work
└── Edition
    ├── EPUB asset
    ├── FB2 asset
    └── MOBI asset
```

Likewise, contributor metadata is normalized without destroying the original values found in source files:

```text
Mikhail Bulgakov
├── M. Bulgakov
├── Bulgakov, M.
└── Михаил Булгаков
```

Yurlib preserves metadata provenance so that imported, inferred, AI-generated, and user-curated values can be distinguished.

---

## Library Sources

Yurlib will support multiple configured library roots.

Examples:

```text
/mnt/books
/mnt/archive
/mnt/incoming
```

A library root may operate in different modes.

### Read-only

Yurlib can index and catalog the files but does not modify or reorganize them.

This is the preferred mode for existing NAS collections.

### Managed

Yurlib may organize, rename, move, or create derived assets according to configured policies.

### Import

Files placed into an incoming location can be processed and moved into managed storage.

---

## High-Speed Ingestion

Large libraries may contain tens or hundreds of thousands of ebook files.

Yurlib is therefore designed around an incremental asynchronous ingestion pipeline rather than a synchronous directory walk.

```text
Filesystem Discovery
        │
        ▼
Change Detection
        │
        ▼
Metadata Extraction
        │
        ▼
Book / Edition Matching
        │
        ▼
Catalog Update
        │
        ▼
Optional AI Enrichment
```

The ingestion system is intended to support:

- bounded concurrency;
- backpressure;
- retries;
- resumable jobs;
- idempotent processing;
- partial failures;
- disconnected NAS storage;
- change detection without reparsing every book;
- progress and operational visibility.

---

## External Library Connectors

External electronic libraries and catalogs are modeled as plugins rather than hard-coded integrations.

Potential connector types include:

- Flibusta;
- OPDS catalogs;
- Project Gutenberg;
- Calibre servers;
- private ebook repositories;
- institutional or organizational catalogs;
- other compatible electronic libraries.

A connector declares the capabilities it supports, such as:

```text
SEARCH
LOOKUP_BY_TITLE
LOOKUP_BY_AUTHOR
LOOKUP_BY_ISBN
GET_METADATA
GET_COVER
GET_FORMAT_LIST
CHECK_AVAILABILITY
DOWNLOAD
```

This allows Yurlib to use metadata-only services, searchable catalogs, or download-capable repositories through the same extension model.

External content is treated as untrusted input and must pass through the normal validation and ingestion pipeline.

Connectors are expected to respect authentication requirements, technical access controls, licensing restrictions, and applicable source policies.

---

## AI Integration

AI is used to assist catalog maintenance, not to silently take control of canonical library data.

Potential AI-assisted capabilities include:

- contributor matching;
- author alias detection;
- title normalization;
- duplicate detection;
- series identification;
- genre classification;
- automatic tags;
- metadata reconciliation;
- semantic search;
- natural-language catalog queries;
- matching local books to external-library results.

Example:

```text
Observed contributor:
King, Stephen

Suggested match:
Stephen King

Confidence:
98%

[Accept] [Reject] [Review Later]
```

Important catalog changes remain deterministic or explicitly reviewable.

The AI layer is intended to support multiple providers, including cloud and locally hosted models.

---

## Format Conversion

Yurlib will support asynchronous ebook conversion.

Examples:

```text
FB2 → EPUB
EPUB → MOBI
MOBI → EPUB
```

Conversion engines are isolated behind an adapter so that the catalog domain is not tied to a specific converter.

Converted files are stored as new assets and do not silently replace originals.

---

## Planned Architecture

The initial architecture is expected to use a small number of clearly bounded services rather than splitting every function into an independent microservice.

```text
                     Web UI
                        │
                        ▼
                  Application API
                        │
       ┌────────────────┼─────────────────┐
       ▼                ▼                 ▼
 Catalog Service  Ingestion Service   AI Service
       │                │                 │
       └────────────┬───┴─────────────────┘
                    │
              Messaging / Jobs
                    │
                    ▼
            Conversion Service
```

The exact service boundaries are subject to architectural review as implementation progresses.

---

## Planned Technology Stack

Current baseline:

- **Java 25**
- **Spring Boot**
- **Angular**
- **PostgreSQL**
- **pgvector**
- **Maven**
- **Docker / Docker Compose** for the initial deployment model
- **Testcontainers**
- **OpenTelemetry**
- **GitHub Actions**
- **Kubernetes / Helm or Kustomize** as an optional future deployment target
- **Terraform** only if infrastructure provisioning is later required

Additional infrastructure will be introduced only when justified by product requirements or measured operational needs.

---

## Deployment Model

The initial supported deployment model is intentionally simple:

```text
Yurlib
  ↓
Docker images
  ↓
Docker Compose
  ↓
Local Linux server / NAS-connected host
```

Kubernetes is an optional future deployment target. If Kubernetes support is added, Yurlib is expected to deploy into an existing cluster using Helm or Kustomize.

Terraform is not part of the initial Yurlib baseline. It would only be introduced if the project later needs to provision infrastructure such as Kubernetes clusters, networking, managed databases, DNS, load balancers, IAM, or object storage.

---

## AI-Native Development

Yurlib is intentionally being built as a reference project for agent-assisted software development.

The engineering workflow is expected to follow:

```text
Requirement
    │
    ▼
Architecture / Design
    │
    ▼
Plane Work Item
    │
    ▼
Planning Agent
    │
    ▼
Implementation Agent(s)
    │
    ▼
Deterministic CI
    │
    ▼
Independent AI Review
    │
    ▼
Human Architectural Review
    │
    ▼
Merge
```

Repository documentation, ADRs, coding standards, tests, agent instructions, and architectural constraints are treated as part of the engineering system rather than informal guidance.

AI agents may propose architectural changes, but they should not silently introduce major frameworks, databases, services, or infrastructure.

---

## Repository Structure

The repository is expected to evolve toward a structure similar to:

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
│
├── infrastructure/
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
│   └── workflows/
│
├── AGENTS.md
└── README.md
```

This structure is not yet a compatibility commitment and may change as the architecture is refined.

---

## Initial Product Milestones

The current sequence is:

1. Define the product concept.
2. Create and configure the GitHub repository.
3. Install and configure the local Plane project-management environment.
4. Define the development environment and AI-native SDLC.
5. Convert the engineering design into Plane work items.
6. Bootstrap the application and CI environment.
7. Implement the first vertical product slice.

The first product vertical slice is expected to prove the core ingestion path:

> Point Yurlib at a library directory and display EPUB, FB2, and MOBI books in a persistent searchable catalog.

---

## Security Principles

Ebook files and external connector responses are treated as untrusted input.

The design must account for threats such as:

- malformed ebook archives;
- ZIP bombs;
- XML external entity attacks;
- path traversal;
- malicious HTML or script content;
- command injection into conversion tools;
- oversized files;
- unsafe filenames;
- compromised external services;
- credential leakage;
- excessive resource consumption.

Containers and plugins should operate with the minimum privileges required.

---

## Documentation

Project design documentation lives under [`docs/`](docs/).

The product concept is intended to be maintained separately from implementation-specific architectural decisions.

Architecture decisions that materially affect the project should be captured as ADRs rather than being buried in source code or chat history.

---

## Contributing

Yurlib is currently in the architecture/bootstrap stage.

Contribution guidelines, coding standards, development environment setup, and pull-request requirements will be published as the engineering environment is established.

Until then, please use GitHub Issues for questions, ideas, and proposed features.

---

## License

A project license has not yet been selected.

Until a license is added, the repository should not be assumed to grant permission to copy, modify, or redistribute the source code.

---

## Name

**Yurlib** combines a personal library concept with a short project name suitable for both the application and its development ecosystem.
