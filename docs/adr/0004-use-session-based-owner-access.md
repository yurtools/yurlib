# ADR-0004: Use Session-Based Owner Access

- Status: Accepted
- Date: 2026-09-29
- Accepted: 2026-09-29
- Decider: Yurlib project owner

## Context

ADR-0003 permits unauthenticated operation only in an explicit loopback-only development mode and requires authenticated owner access before Yurlib listens on a shared-network interface. The M1 web workflow is a same-origin Angular application that configures a root, starts and polls scans, searches the catalog, and downloads original assets.

The first implementation needs one owner rather than registration, account recovery, multiple roles, or an external identity provider. It must keep credentials out of Git, protect state-changing requests against cross-site request forgery, preserve ordinary browser downloads, and fail closed when shared-network credentials are absent.

Selecting the authentication model and adding Spring Security are architecturally significant changes under the repository change-control policy.

## Decision

Use Spring Security with a server-side HTTP session for one deployment-defined owner.

- Shared-network mode is the secure default. It requires an owner username and password supplied through deployment secrets or environment configuration; no usable credential is committed to the repository.
- The password is encoded for verification in memory and is never persisted in the catalog database or returned by an API.
- A same-origin login endpoint creates a session using an `HttpOnly` cookie. Logout invalidates the session and clears authentication state.
- Spring Security's CSRF protection remains enabled. The server exposes the CSRF token through the conventional `XSRF-TOKEN` cookie so Angular sends it in the `X-XSRF-TOKEN` header on unsafe relative requests.
- All M1 `/api/**` operations require the `OWNER` authority. This includes root configuration, scan start, catalog reads, job polling, and original downloads.
- Health and readiness remain available without owner authentication, but sensitive actuator details and all other actuator endpoints require owner access.
- The Angular application shows an explicit owner login state and does not store the password. Same-origin downloads rely on the session cookie rather than credentials in a URL.
- The `loopback-dev` Spring profile permits unauthenticated API access only when the effective server bind address resolves to a loopback address. Startup fails if that profile is combined with a non-loopback or wildcard bind.
- Shared-network deployment requires TLS termination at Yurlib or a trusted reverse proxy. Production session cookies are secure, HTTP-only, and same-site.
- Authentication failures use generic responses and do not disclose whether a username exists. Authentication material is excluded from logs and metrics.

### Scope

M1 has one owner authority. Registration, password reset, persisted accounts, multi-user authorization, OAuth 2.0/OpenID Connect, public catalog access, API tokens, and identity-provider integration are deferred.

## Consequences

### Positive

- Spring Security supplies maintained authentication, authorization, session-fixation protection, security headers, logout, and CSRF defenses rather than custom security primitives.
- Passwords and bearer credentials do not need to be stored in browser storage or embedded in download URLs.
- The session works consistently for Angular HTTP calls and ordinary same-origin asset links.
- The application remains independent of an external identity service for the single-owner M1 deployment.
- A fail-closed default prevents an accidental unauthenticated shared-network deployment.

### Negative

- Spring Security becomes an application dependency and adds a security filter chain that controller tests must account for.
- Deployments must provide an owner credential and TLS termination before exposing Yurlib beyond loopback.
- Server restarts invalidate in-memory sessions.
- A single deployment-defined owner does not support multiple people, delegated roles, external single sign-on, or per-user auditing.
- The Angular workflow gains login, logout, unauthenticated, and expired-session states.

## Alternatives considered

### HTTP Basic authentication

Rejected as the primary browser workflow because credentials are sent on every request, logout behavior is poor, browser prompting is inconsistent for API-driven SPAs, and it provides a weaker user experience for session expiry. TLS would still be mandatory.

### Static bearer owner token

Rejected because the token would need browser storage or repeated entry, ordinary download links cannot attach an authorization header, and placing the token in a URL would leak it through history and logs.

### Authentication delegated only to a reverse proxy

Rejected for M1 because it would make safe application behavior depend on an additional deployment component and trusted-header configuration. A future ADR may add an external identity-provider or proxy integration.

### OAuth 2.0 or OpenID Connect

Deferred because it introduces provider configuration and operational dependencies that are not justified for the initial single-owner deployment.

### Custom servlet authentication filter without Spring Security

Rejected because authentication, session fixation, CSRF, security headers, failure handling, and authorization are security-sensitive framework concerns that should not be reimplemented locally.

## Supersession

None. This ADR resolves the authentication-model decision deferred by the development-environment document and specializes ADR-0003's owner-access boundary.
