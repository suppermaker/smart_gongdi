# ADR 0003: MCP Tool Plane

- Status: Accepted
- Date: 2026-10-28

## Context

The Python agent runtime needs typed, least-privilege access to authoritative Java
control-plane data without allowing model-controlled tenant, project, identity,
role, or credential arguments. MCP calls must remain outside Temporal workflow
history and agents must not directly perform consequential state transitions.

## Decision

Use official Java MCP SDK 2.1.0 with its core servlet Streamable HTTP transport
at `/mcp`, and official Python `mcp` 2.3 stable client. The transport validates
Host and Origin and derives trusted context from a signed Bearer service token.
The internal token uses HMAC-SHA256 and includes tenant, project, actor, task,
role, permissions, and expiry. Missing, expired, or tampered tokens fail closed.
No usable default signing key is provided.

Expose role-scoped read tools and Supervisor-only proposal-draft tools. Every
tool validates task version, graph revision, namespace, and task/risk linkage.
Proposal tools return drafts and never mutate authoritative risk state. Every
successful, rejected, and failed call is audited in an independent transaction
using an argument digest rather than raw credentials.

Temporal Activities call MCP through an adapter. Token providers and HTTP
headers are Activity-local; workflow contracts contain only opaque task identity,
role/version information, and no secret or token.

## Consequences

- HMAC is an internal service-token mechanism, not OAuth 2.1.
- Production must migrate to OAuth 2.1 workload identity with issuer, audience,
  scope, rotation, and revocation validation.
- Docker, PostgreSQL, Java MCP, Python MCP, and real Temporal integration are
  deterministic release gates and fail rather than skip when unavailable.
