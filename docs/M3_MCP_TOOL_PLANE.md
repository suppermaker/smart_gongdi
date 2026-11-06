# M3 MCP Tool Plane

M3 exposes the Java control plane through the official Java MCP SDK 2.1.0 core
servlet transport at `/mcp` using Streamable HTTP. The Python runtime uses the
official `mcp` 2.3 stable client and injects Authorization and timeouts in the
HTTP transport layer.

## Security boundary

`Authorization: Bearer` carries an internal HMAC-SHA256 service token with
`tenant_id`, `project_id`, `actor_id`, `task_id`, `role`, `permissions`, and
`exp`. `SITEPILOT_MCP_HMAC_SECRET` must be supplied by deployment and must be at
least 32 characters. This is not OAuth 2.1 and must not be presented as user
authorization; production should replace it with an OAuth 2.1 authorization
server, audience/issuer validation, key rotation, and short-lived workload
identity.

Host validation is mandatory and Origin is rejected unless explicitly allowed.
Identity/namespace/role/credentials are absent from every tool schema and are
injected from verified transport context.

## Tools and authority

Read tools are `get_project_context`, `get_risk`, `get_investigation_task`, and
`list_risk_evidence`. `propose_human_question` is Supervisor-only and returns a
DRAFT with `authoritativeStateChanged=false`; it never mutates risk state.
Every call validates the authenticated task namespace, task/risk association,
expected task version, and expected graph revision.

All successful, rejected, and failed tool calls write `MCP_TOOL_CALL` audit
records in a `REQUIRES_NEW` transaction with actor/task/role/tool/status and a
SHA-256 argument digest. Tokens and raw secrets are never logged.

## Runtime boundary

Temporal workflows contain no token and perform no MCP/network I/O. Activities
use `ControlPlaneAdapter`; deterministic tests retain `FakeControlPlaneAdapter`,
while production can construct `McpControlPlaneAdapter` with an Activity-local
token provider.

## Verification

Java RANDOM_PORT integration tests use the official Java client over real Streamable HTTP and Testcontainers PostgreSQL. make m3-test starts PostgreSQL, the Java server on 18080, and real Temporal; the official Python client then parses structured content both directly and through a Temporal Activity. All services are stopped by a shell trap and unavailable dependencies fail the gate.
