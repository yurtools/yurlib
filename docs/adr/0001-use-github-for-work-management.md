# ADR-0001: Use GitHub Issues and Projects for Work Management

- Status: Accepted
- Date: 2026-09-28
- Last reviewed: 2026-09-29
- Decider: Yurlib project owner

## Context

Yurlib needs a durable way to track requirements, architecture work, engineering tasks, implementation status, and review evidence. The initial development-environment design selected a separate local Plane installation.

The project is currently maintained through GitHub, with source code, design documents, ADRs, pull requests, and repository protection already hosted there. Operating a separate Plane installation would add another service, workflow, permission model, and source of project state before the project needs that complexity.

## Decision

Yurlib will use GitHub as its work-management system:

- GitHub Issues contain actionable work, acceptance criteria, design references, and implementation evidence.
- GitHub Projects organizes issues and pull requests across the project workflow.
- Pull requests link or close their corresponding issues where practical.
- Branch names, commits, and pull requests reference GitHub issue numbers where practical.
- Architecture and product decisions remain authoritative in version-controlled documents and ADRs; Issues and Projects track the work but do not replace the design baseline.

The configured initial project status workflow deliberately uses GitHub's three standard states:

```text
Todo
   ↓
In Progress
   ↓
Done
```

Design readiness, automated checks, AI review, and human review remain visible in issue and pull-request evidence rather than separate Project status values. Additional workflow states should be added only when observed coordination needs justify them.

Plane is not required for the initial Yurlib engineering environment. Adopting another work-management system later requires a new ADR and an explicit migration plan.

## Consequences

### Positive

- Work, code, reviews, and delivery evidence remain in one platform.
- No separate Plane deployment, backup, upgrade, authentication, or integration is required.
- Issue-to-branch and issue-to-pull-request traceability uses native GitHub relationships.
- The initial process remains proportionate to a single-maintainer project.
- The small status set keeps project maintenance low while issues and pull requests retain detailed evidence.

### Negative

- GitHub Projects may provide less specialized planning functionality than Plane.
- Project workflow configuration depends on GitHub-hosted features.
- The Project view does not expose separate design and review stages at a glance.
- A later migration would require preserving issue links and decision traceability.

## Alternatives considered

### Local Plane installation

Rejected for the initial environment because its additional operational and synchronization overhead is not justified at the current project scale.

### Repository documents only

Rejected because documents are appropriate for durable requirements and decisions but are inefficient for assigning, sequencing, and tracking actionable work.
