# Yurlib Agent Skills — Approved Sources and Safe Installation Procedure

**Project:** Yurlib  
**Document:** Agent Skills Catalog and Installation Policy  
**Status:** Draft for approval  
**Version:** 0.2  
**Last Updated:** 2026-09-23  
**Recommended Repository Path:** `docs/architecture/agent-skills.md`

---

## 1. Purpose

This document defines:

- which third-party Agent Skills are initially approved for Yurlib;
- where those skills come from;
- what each skill is intended to do;
- which skills belong in the Yurlib repository versus a developer's global environment;
- how third-party skills must be reviewed before they are trusted;
- how the `skill-security-review` static-analysis tool is used;
- how approved skills are installed and committed;
- how skill updates are reviewed.

Agent Skills are treated as executable engineering dependencies.

A skill may contain:

- instructions injected into an AI agent's context;
- scripts;
- shell commands;
- templates;
- executable code;
- references to external services.

For that reason, a skill must not be trusted merely because it appears on a public registry or has a high install count.

---

# 2. Project Skill Location

Yurlib project-level skills should use the portable Agent Skills location:

```text
yurlib/
└── .agents/
    └── skills/
```

Project-level skills are committed to Git so that humans, CI systems, and supported coding agents use the same reviewed skill content.

Example:

```text
yurlib/
├── .agents/
│   └── skills/
│       ├── java-spring-best-practices/
│       ├── spring-boot-testing/
│       ├── system-design/
│       └── architecture-decision/
│
├── docs/
├── AGENTS.md
└── README.md
```

Do not use `-g` for skills that are part of the Yurlib engineering environment.

Global installation is reserved for personal/workstation tooling such as the skill security auditor.

---

# 3. Initial Approved Skill Set

The following is the initial proposed Yurlib skill set.

## 3.1 Java / Spring Best Practices

**Skill:** `java-spring-best-practices`  
**Source Repository:** `https://github.com/cosbort/agent-skills`  
**Registry Page:** `https://www.skills.sh/cosbort/agent-skills/java-spring-best-practices`

### Purpose

Provide current Java/Spring implementation guidance, including:

- modern Java language features;
- Spring Boot 3.5/4.x practices;
- records for DTOs;
- virtual-thread awareness;
- `ProblemDetail`;
- modern HTTP clients;
- Testcontainers;
- structured logging;
- Micrometer/observability;
- container-aware development practices.

### Yurlib Usage

Used primarily by:

- Java implementation agents;
- Java reviewers;
- backend planning agents.

### Install Command After Review

```bash
npx skills add ./_skill-review/cosbort-agent-skills \
  --skill java-spring-best-practices
```

The local path in this example refers to the reviewed repository copy described later in this document.

---

## 3.2 Spring Boot Testing

**Skill:** `spring-boot-testing`  
**Source Repository:** `https://github.com/marcelorodrigo/agent-skills`  
**Registry Page:** `https://www.skills.sh/marcelorodrigo/agent-skills/spring-boot-testing`

### Purpose

Provide focused Spring Boot testing guidance, including:

- unit versus slice versus integration tests;
- modern Spring Boot 4 testing APIs;
- Testcontainers;
- realistic infrastructure tests;
- avoiding excessive mocking;
- readable assertions and test organization.

### Yurlib Usage

Used primarily by:

- Java implementation agents;
- test review agents;
- code review agents.

### Install Command After Review

```bash
npx skills add ./_skill-review/marcelorodrigo-agent-skills \
  --skill spring-boot-testing
```

---

## 3.3 System Design

**Skill:** `system-design`  
**Source Repository:** `https://github.com/jwynia/agent-skills`  
**Registry Page:** `https://www.skills.sh/jwynia/agent-skills/system-design`

### Purpose

Guide architecture work from validated requirements toward:

- component boundaries;
- interfaces;
- integration points;
- trade-off analysis;
- scalability decisions;
- explicit architectural constraints.

The skill emphasizes deriving architecture from requirements rather than selecting technology first.

### Yurlib Usage

Used primarily by:

- architect/planner agent;
- implementation planning;
- feature decomposition;
- design-document reviews.

### Install Command After Review

```bash
npx skills add ./_skill-review/jwynia-agent-skills \
  --skill system-design
```

---

## 3.4 Architecture Decision

**Skill:** `architecture-decision`  
**Source Repository:** `https://github.com/jwynia/agent-skills`  
**Registry Page:** `https://www.skills.sh/jwynia/agent-skills/architecture-decision`

### Purpose

Guide structured architecture decisions and ADR creation.

Typical use cases:

- evaluating technology choices;
- comparing architectural patterns;
- documenting trade-offs;
- creating ADRs;
- evaluating technical debt.

### Yurlib Usage

Used when an implementation would introduce or change:

- service boundaries;
- storage technology;
- messaging infrastructure;
- search infrastructure;
- framework choices;
- deployment architecture;
- other major technical dependencies.

### Install Command After Review

Because this skill comes from the same repository as `system-design`, review the repository once and install both approved skills from the same reviewed copy:

```bash
npx skills add ./_skill-review/jwynia-agent-skills \
  --skill system-design \
  --skill architecture-decision
```

---

## 3.5 GitHub Actions Author

**Skill:** `github-actions-author`  
**Source Repository:** `https://github.com/mthines/agent-skills`  
**Registry Page:** `https://www.skills.sh/mthines/agent-skills/github-actions-author`

### Purpose

Create and audit GitHub Actions workflows using current CI/CD and security practices.

Useful areas include:

- workflow structure;
- reusable workflows;
- diagnostic output;
- permissions;
- CI failure visibility;
- workflow security;
- agent-friendly CI logs.

### Yurlib Usage

Used primarily by:

- infrastructure agent;
- CI/CD implementation agent;
- CI review agent.

### Install Command After Review

```bash
npx skills add ./_skill-review/mthines-agent-skills \
  --skill github-actions-author
```

---

## 3.6 Code Review Orchestrator

**Skill:** `code-review`  
**Source Repository:** `https://github.com/openai/codex`  
**Registry Page:** `https://www.skills.sh/openai/codex/code-review`

### Purpose

Provide an independent multi-agent code-review workflow.

The current skill is designed as an orchestrator that can dispatch specialized review subagents and consolidate their findings.

### Yurlib Usage

Used primarily by:

- independent AI review;
- pull-request review;
- pre-human-review validation.

### Important Constraint

This skill should be used only in environments that support the agent/subagent behavior it expects.

It is not a substitute for:

- deterministic CI;
- human architectural review;
- Yurlib-specific security review.

### Install Command After Review

```bash
npx skills add ./_skill-review/openai-codex \
  --skill code-review
```

---

# 4. Conditional Skills

The following skills are approved in principle but should not be installed until the related capability is actively introduced into Yurlib.

## 4.1 Deployment Tooling Policy

Yurlib's initial supported deployment model is:

```text
Application build
      ↓
Docker images
      ↓
Docker Compose
      ↓
Local server / NAS-connected host
```

Terraform is **not required** for this model.

If Kubernetes support is added later, the expected deployment model is:

```text
Existing Kubernetes cluster
        ↓
Helm or Kustomize
        ↓
Yurlib workloads
```

Terraform is still not required merely to deploy Yurlib into an existing Kubernetes cluster.

Terraform becomes relevant only if Yurlib also needs to provision infrastructure such as:

- Kubernetes clusters;
- cloud networking;
- managed databases;
- DNS;
- load balancers;
- cloud IAM;
- object storage;
- other persistent cloud resources.

Therefore Terraform is not part of the initial Yurlib engineering environment or initial project skill set.

### Initial Deployment Skill Position

At project bootstrap, Yurlib does not require a Terraform skill.

| Capability | Initial Status | Skill Needed Initially |
|---|---|---|
| Docker image creation | Required | Evaluate a Docker/container skill only if repeated workflow warrants it |
| Docker Compose | Required | Prefer repository-specific instructions initially |
| Kubernetes | Optional future | No |
| Helm | Optional future | No |
| Terraform | Optional future infrastructure provisioning | No |

The initial Docker/Compose procedures are simple enough to keep in:

```text
AGENTS.md
.github/instructions/
docs/architecture/
```

rather than adding a generic deployment skill immediately.

A dedicated Docker or Compose skill should be added only when the project begins repeating enough nontrivial container workflows to justify one.

---

## 4.2 Kubernetes / Helm Skills — Deferred

Kubernetes and Helm skills are not part of the initial Yurlib skill inventory.

They should be evaluated only if Kubernetes becomes an officially supported deployment target.

If Kubernetes support is introduced, prefer reviewed skills that cover:

- Helm chart authoring;
- Kubernetes security contexts;
- resource requests and limits;
- liveness/readiness/startup probes;
- persistent volumes;
- ConfigMaps and Secrets;
- ingress configuration;
- upgrade and rollback behavior.

Any third-party Kubernetes or Helm skill must follow the same quarantine, static-analysis, and manual-review process defined in this document.

---

## 4.3 Terraform Skills — Deferred

**Skills:** `terraform-style-guide`, `terraform-test`  
**Source Repository:** `https://github.com/hashicorp/agent-skills`

### Purpose

These skills may be useful if Yurlib later provisions infrastructure with Terraform.

They are **not required** for:

- local Docker Compose deployment;
- running containers on an existing Linux host;
- using NAS-mounted storage;
- deploying Yurlib into an existing Kubernetes cluster;
- Helm-based application deployment.

### Install When

Install only after an approved architectural decision introduces Terraform as an active Yurlib infrastructure dependency.

### Install Commands After Review

```bash
npx skills add ./_skill-review/hashicorp-agent-skills \
  --skill terraform-style-guide \
  --skill terraform-test
```

---

## 4.4 Agentic Actions Auditor

**Skill:** `agentic-actions-auditor`  
**Source Repository:** `https://github.com/trailofbits/skills`  
**Registry Page:** `https://www.skills.sh/trailofbits/skills/agentic-actions-auditor`

### Purpose

Audit GitHub Actions workflows that invoke AI coding agents.

It is intended to identify risks such as:

- attacker-controlled issue or PR content entering agent prompts;
- unsafe `pull_request_target` workflows;
- excessive agent permissions;
- unsafe tool access;
- indirect prompt injection through GitHub event data;
- hidden AI execution inside composite or reusable workflows.

### Install When

Yurlib begins running AI coding/review agents automatically from GitHub Actions.

### Install Command After Review

```bash
npx skills add ./_skill-review/trailofbits-skills \
  --skill agentic-actions-auditor
```

---

# 5. Global Security Audit Tool

## 5.1 skill-security-review

**Tool / Skill:** `skill-security-review`  
**Source Repository:** `https://github.com/dkleptsov/skill-security-review`  
**License:** Apache-2.0

This tool performs static analysis of Agent Skills without executing the target skill.

It checks for categories including:

- network egress;
- shell/process execution;
- Python subprocess use;
- dynamic code execution;
- install-time scripts;
- secrets/environment access;
- symlinks;
- binaries and opaque files;
- executable entry points;
- suspicious prompt text / prompt-injection indicators.

The scanner identifies potentially dangerous capabilities. It does not certify that a skill is safe.

Manual review is still required.

---

# 6. Bootstrapping the Security Scanner

There is an unavoidable trust-bootstrap problem:

> A security scanner cannot safely be installed by blindly executing the same untrusted installation process it is intended to inspect.

Therefore the auditor itself should be bootstrapped separately.

## 6.1 Clone the Auditor

Use a dedicated trusted-tools directory:

```bash
mkdir -p ~/.local/share/yurlib-security-tools

git clone --depth 1 \
  https://github.com/dkleptsov/skill-security-review \
  ~/.local/share/yurlib-security-tools/skill-security-review
```

Do not run `npm install`.

Do not execute unknown scripts during this inspection.

The scanner is designed to use Node's standard library and does not require third-party package installation.

---

## 6.2 Inspect the Auditor Manually

At minimum inspect:

```bash
cd ~/.local/share/yurlib-security-tools/skill-security-review

find . -maxdepth 3 -type f -print

less SKILL.md
less scripts/scan.mjs
less references/patterns.md
```

Check particularly for:

- unexpected network calls;
- shell execution;
- credential access;
- package installation;
- lifecycle scripts;
- binaries;
- symlinks leaving the repository.

Only after this one-time manual review should the scanner be treated as trusted tooling.

---

## 6.3 Optional Global Skill Installation

After reviewing the auditor itself, it may also be installed globally for agent use:

```bash
npx skills add dkleptsov/skill-security-review -g
```

The trusted standalone clone should still be retained because it provides a known direct path to the static scanner.

Recommended scanner invocation:

```bash
node ~/.local/share/yurlib-security-tools/skill-security-review/scripts/scan.mjs <target>
```

For CI-like behavior:

```bash
node ~/.local/share/yurlib-security-tools/skill-security-review/scripts/scan.mjs \
  <target> \
  --fail-on high
```

---

# 7. Mandatory Safe Installation Procedure

Third-party skills must not be installed directly from the internet into Yurlib before review.

Do not use this as the first step:

```bash
npx skills add https://github.com/someone/some-skills \
  --skill some-skill
```

The safe process is:

```text
Discover
   ↓
Clone into quarantine
   ↓
Record exact commit
   ↓
Static scan
   ↓
Manual review
   ↓
Approve / reject
   ↓
Install from reviewed local copy
   ↓
Review resulting repository diff
   ↓
Commit
```

---

# 8. Step-by-Step Review Procedure

Assume the Yurlib repository is:

```text
~/projects/yurlib
```

## 8.1 Create a Temporary Review Area

From the repository root:

```bash
cd ~/projects/yurlib

mkdir -p _skill-review
```

`_skill-review/` must not be committed.

Add it to `.gitignore`:

```text
_skill-review/
```

---

## 8.2 Clone the Candidate Repository

Example:

```bash
git clone --depth 1 \
  https://github.com/cosbort/agent-skills \
  _skill-review/cosbort-agent-skills
```

Cloning retrieves source for inspection.

Do not:

```text
npm install
pnpm install
yarn
pip install
make
./install.sh
```

Do not execute scripts from the candidate repository.

---

## 8.3 Record the Reviewed Commit

```bash
git -C _skill-review/cosbort-agent-skills rev-parse HEAD
```

Record this hash in the installation/change record or pull-request description.

Example:

```text
Source:
https://github.com/cosbort/agent-skills

Reviewed commit:
<commit SHA>
```

This establishes exactly which content was evaluated.

---

## 8.4 Run Static Analysis

Run the trusted scanner against the cloned repository or the specific skill directory.

Example:

```bash
node ~/.local/share/yurlib-security-tools/skill-security-review/scripts/scan.mjs \
  _skill-review/cosbort-agent-skills
```

For stricter automation:

```bash
node ~/.local/share/yurlib-security-tools/skill-security-review/scripts/scan.mjs \
  _skill-review/cosbort-agent-skills \
  --fail-on high
```

JSON output may be retained for review:

```bash
node ~/.local/share/yurlib-security-tools/skill-security-review/scripts/scan.mjs \
  _skill-review/cosbort-agent-skills \
  --json \
  > /tmp/cosbort-agent-skills-security.json
```

---

# 9. How to Interpret Static Findings

A finding does not automatically mean a skill is malicious.

For example:

```text
curl
fetch
child_process
subprocess
exec
```

may be legitimate for some skills.

The reviewer must determine:

1. Why is the capability required?
2. Is it executed automatically?
3. Is it executed only when the user explicitly requests an action?
4. Can it access repository secrets or host credentials?
5. Can untrusted input reach the command?
6. Is the behavior appropriate for Yurlib?

High-risk findings must be understood before approval.

Unknown or unexplained high-risk behavior means:

```text
REJECT
```

until resolved.

---

# 10. Manual Review Checklist

Static analysis supplements manual inspection.

For every new third-party skill, inspect the following.

## 10.1 SKILL.md

Read the complete file.

Look for instructions that:

- override project security policy;
- request unrestricted tool permissions;
- attempt to expose secrets;
- ask agents to ignore higher-level instructions;
- automatically upload data;
- automatically execute shell commands;
- make network requests unrelated to the skill's stated purpose.

---

## 10.2 Executable Files

List executable or script-like content:

```bash
find _skill-review/<repo> \
  -type f \
  \( -name "*.sh" -o \
     -name "*.py" -o \
     -name "*.js" -o \
     -name "*.mjs" -o \
     -name "*.cjs" -o \
     -name "*.ts" \) \
  -print
```

Review scripts associated with the desired skill.

---

## 10.3 Package Lifecycle Hooks

If a candidate contains `package.json`, inspect:

```text
preinstall
install
postinstall
prepare
```

Unexpected installation-time execution is a significant risk.

---

## 10.4 Symlinks

Inspect:

```bash
find _skill-review/<repo> -type l -ls
```

Symlinks escaping the repository tree must be understood and normally rejected.

---

## 10.5 Binaries and Opaque Content

Unexpected:

- executables;
- native libraries;
- archives;
- minified bundles;
- encoded payloads;

require additional scrutiny.

Prefer skills whose behavior is represented as readable text and small inspectable scripts.

---

# 11. Install Only the Reviewed Skill

After approval, install from the reviewed local clone rather than fetching the repository again from the network.

Example:

```bash
cd ~/projects/yurlib

npx skills add ./_skill-review/cosbort-agent-skills \
  --skill java-spring-best-practices
```

Using a local reviewed path ensures that the installer receives the same repository contents that were scanned and manually inspected.

Do not run the remote-source installation command after reviewing a local clone, because upstream content may have changed between review and installation.

---

# 12. Review the Installed Diff

After installation:

```bash
git status
git diff -- .agents skills-lock.json
```

Depending on the skills CLI and agent configuration, additional skill-related files or links may be created.

Confirm:

- only expected skills were added;
- no unrelated skill was installed;
- no unexpected executable appeared;
- no unrelated project files changed.

Then inspect the installed skill:

```bash
find .agents/skills/<skill-name> -maxdepth 3 -type f -print
```

---

# 13. Commit the Skill

Project-level skills should be committed.

Example:

```bash
git add .agents/skills
git add skills-lock.json 2>/dev/null || true

git commit -m "Add reviewed Java Spring agent skill"
```

If the installer creates agent-specific symlinks or generated metadata, review those files before deciding whether they belong in Git.

---

# 14. Proposed Initial Installation Order

Do not install all skills simultaneously.

Install and validate them incrementally.

Recommended order:

```text
1. skill-security-review        global/trusted security tooling

2. system-design               project
3. architecture-decision       project

4. java-spring-best-practices  project
5. spring-boot-testing         project

6. github-actions-author       project

7. code-review                 project
```

Later, only when justified:

```text
8. Kubernetes / Helm skill     if Kubernetes becomes a supported deployment target
9. terraform-style-guide       only if Terraform is adopted for infrastructure provisioning
10. terraform-test             only if Terraform is adopted for infrastructure provisioning
11. agentic-actions-auditor    when agentic GitHub Actions begin
```

Installing skills incrementally makes unexpected behavior easier to identify.

---

# 15. Recommended Initial Project Skill Inventory

After the initial bootstrap:

```text
.agents/
└── skills/
    ├── architecture-decision/
    ├── code-review/
    ├── github-actions-author/
    ├── java-spring-best-practices/
    ├── spring-boot-testing/
    └── system-design/
```

Conditional additions:

```text
.agents/
└── skills/
    ├── agentic-actions-auditor/
    ├── kubernetes-or-helm-skill/   # only if Kubernetes support is added
    ├── terraform-style-guide/      # only if Terraform is adopted
    └── terraform-test/             # only if Terraform is adopted
```

Yurlib-specific skills will be added separately as repeated workflows emerge.

---

# 16. Skills That Should Be Yurlib-Specific

Do not search indefinitely for public skills that attempt to encode Yurlib's own domain and SDLC.

The following should eventually be created inside the Yurlib repository:

```text
plane-work-item
add-library-connector
implement-ingestion-stage
change-yurlib-event-contract
yurlib-pr-readiness
review-ebook-security
```

These procedures depend on Yurlib-specific concepts such as:

- Work / Edition / Asset;
- contributor aliases;
- connector SPI;
- NAS behavior;
- ingestion idempotency;
- Yurlib Plane workflow;
- Yurlib architectural decisions.

Generic third-party skills should not be allowed to redefine these concepts.

---

# 17. Skill Precedence

Project policy takes precedence over generic skill guidance.

The intended hierarchy is:

```text
System / platform safety rules
        ↓
Yurlib AGENTS.md
        ↓
Yurlib architecture and ADRs
        ↓
Yurlib-specific skills
        ↓
Approved generic third-party skills
```

Example:

If a generic Java skill recommends a pattern that conflicts with an accepted Yurlib ADR, the ADR wins.

A skill is guidance, not architectural authority.

---

# 18. Updating Skills

Do not automatically update third-party skills directly on `main`.

A skill update is a dependency change and must be reviewed.

Use:

```text
Review upstream change
        ↓
Clone/fetch new version into quarantine
        ↓
Record commit
        ↓
Run static scanner again
        ↓
Inspect changed SKILL.md/scripts
        ↓
Install reviewed update
        ↓
Review Git diff
        ↓
Pull request
```

Do not assume that because version N was safe, version N+1 is safe.

---

# 19. Avoid Blind `skills update`

For Yurlib, prefer explicit per-skill updates rather than bulk updating every installed skill without review.

Reasons:

- upstream behavior can change;
- additional scripts can appear;
- instructions can change agent behavior;
- repository skill collections may gain unrelated content.

Every update should remain attributable to a reviewed upstream commit.

---

# 20. Pull Request Requirements for Skill Changes

A pull request that adds or updates a third-party skill should include:

```text
Skill:
Source repository:
Skill name:
Reviewed commit:
Registry page:
Static scan result:
Manual review completed:
High findings:
Medium findings:
Decision:
Reason for adding/updating:
```

Example:

```text
Skill: spring-boot-testing
Source: https://github.com/marcelorodrigo/agent-skills
Reviewed commit: abcdef123...
Static scan: PASS, no unexplained high findings
Manual review: completed
Purpose: Spring Boot 4 testing guidance
```

---

# 21. Optional Future CI Control

Once the project contains stable project-level skills, Yurlib may add a CI job that statically scans:

```text
.agents/skills/**
```

on every pull request that changes agent skills.

Conceptually:

```bash
node tools/skill-security-review/scripts/scan.mjs \
  .agents/skills \
  --fail-on high
```

For reproducibility, the scanner used by CI should itself be pinned and reviewed rather than downloaded dynamically during every CI run.

---

# 22. Telemetry Note

The `skills` CLI documents anonymous installation telemetry.

A developer who does not want that telemetry should set:

```bash
export DISABLE_TELEMETRY=1
```

before using the CLI.

This is optional project policy unless later changed.

---

# 23. Current Approved Sources Summary

| Skill | Source | Scope | Timing |
|---|---|---|---|
| `skill-security-review` | `dkleptsov/skill-security-review` | Global security tooling | Now |
| `system-design` | `jwynia/agent-skills` | Project | Now |
| `architecture-decision` | `jwynia/agent-skills` | Project | Now |
| `java-spring-best-practices` | `cosbort/agent-skills` | Project | Now |
| `spring-boot-testing` | `marcelorodrigo/agent-skills` | Project | Now |
| `github-actions-author` | `mthines/agent-skills` | Project | Now |
| `code-review` | `openai/codex` | Project | Now, if compatible with chosen review runtime |
| Kubernetes / Helm skill | TBD after review | Project | Deferred; only if Kubernetes becomes a supported deployment target |
| `terraform-style-guide` | `hashicorp/agent-skills` | Project | Deferred; only if Terraform is adopted for infrastructure provisioning |
| `terraform-test` | `hashicorp/agent-skills` | Project | Deferred; only if Terraform is adopted for infrastructure provisioning |
| `agentic-actions-auditor` | `trailofbits/skills` | Project | When AI runs in GitHub Actions |

---

# 24. Security Rule

The central rule is:

> **Never execute, install, or expose an unreviewed third-party skill to an agent with meaningful permissions.**

A public skill registry is a discovery mechanism, not a trust boundary.

Static analysis reduces risk but does not replace human review.

Yurlib should prefer:

1. official/vendor-maintained skills;
2. small inspectable skills;
3. skills with narrow permissions;
4. skills whose source is version-controlled;
5. skills that do not require opaque binaries or install-time execution.

---

# 25. Immediate Procedure

For the current Yurlib bootstrap, perform the following:

```text
1. Bootstrap and manually inspect skill-security-review.

2. Clone jwynia/agent-skills into _skill-review/.
3. Run static analysis.
4. Manually inspect system-design and architecture-decision.
5. Install both from the reviewed local clone.

6. Clone cosbort/agent-skills.
7. Scan and inspect java-spring-best-practices.
8. Install from the local reviewed clone.

9. Clone marcelorodrigo/agent-skills.
10. Scan and inspect spring-boot-testing.
11. Install from the local reviewed clone.

12. Clone mthines/agent-skills.
13. Scan and inspect github-actions-author.
14. Install from the local reviewed clone.

15. Review OpenAI Codex code-review compatibility with the selected agent runtime.
16. Scan and install it only if it fits the Yurlib review workflow.

17. Commit reviewed project skills.
18. Record source commits in the skill-change PR.
```

Terraform remains explicitly outside the initial Yurlib baseline. Docker Compose is the default supported deployment model.

Kubernetes/Helm skills should be evaluated only if Kubernetes becomes a supported deployment target.

Terraform skills should be installed only if a later architectural decision requires Yurlib to provision infrastructure itself.

Agentic GitHub Actions skills remain deferred until AI agents are invoked automatically from GitHub workflows.
