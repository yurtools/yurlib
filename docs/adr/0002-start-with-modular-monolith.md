# ADR-0002: Start with a Modular Monolith

- Status: Accepted
- Date: 2026-09-28
- Decider: Yurlib project owner

## Context

Yurlib defines catalog, ingestion, conversion, connector, and AI capabilities as distinct logical responsibilities. The project is at bootstrap stage and does not yet have measured scaling, deployment, team-ownership, or independent-release requirements that justify multiple backend deployables.

Creating several services immediately would introduce distributed transactions, network contracts, deployment coordination, local orchestration, and additional observability requirements before product behavior has been validated.

## Decision

Yurlib will start with:

- one Spring Boot backend deployable named `yurlib-server`;
- one separately built Angular frontend named `yurlib-web`;
- one PostgreSQL database owned by the backend;
- explicit domain, application, API, and infrastructure boundaries inside the backend;
- logical modules for catalog, ingestion, conversion, connectors, and AI as those capabilities are introduced;
- synchronous in-process calls by default and PostgreSQL-backed durable jobs when asynchronous persistence is required;
- no message broker until a demonstrated requirement justifies one.

Internal boundaries must avoid direct coupling that would prevent later extraction. A logical module may become a separately deployed service through a future ADR when at least one of these conditions applies:

- independent scaling is required and measured;
- failure isolation cannot be achieved adequately in-process;
- security or trust boundaries require process isolation;
- independent release cadence provides material value;
- ownership and operational boundaries justify the added distributed-system cost.

## Consequences

### Positive

- The first vertical slice can be implemented and operated with minimal distributed-system overhead.
- Transactions and data consistency remain straightforward while the domain model is evolving.
- Local development, integration testing, and deployment stay simple.
- Explicit internal boundaries preserve a path to later extraction.

### Negative

- Logical modules share a process and deployment lifecycle.
- Poorly enforced boundaries could become accidental coupling.
- Scaling one responsibility independently requires later extraction.

ArchUnit and package-level rules will enforce the important internal boundaries.

## Alternatives considered

### Multiple backend services at bootstrap

Rejected because current requirements do not justify the operational and consistency costs.

### Single undifferentiated application

Rejected because it would not preserve the explicit responsibility and ownership boundaries required by the architecture.

## Supersession

None.
