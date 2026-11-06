# Security

- Trusted tenant/project/actor context is accepted only by the Java boundary and is checked for every read model and SSE subscription.
- SSE namespace parameters must equal trusted headers; asynchronous polling uses the captured immutable namespace and never reads a ThreadLocal.
- Agent/tool inputs do not accept identity, credentials, database URLs, or storage roots.
- Approval is optimistic-lock protected and requires an idempotency key. Agents cannot execute L2 without approval and cannot perform L3.
- Trace attributes are allowlisted identifiers/stages only. Tokens, secrets, authorization headers, raw evidence, prompts, and payloads are forbidden.
- Demo passwords are local defaults only. Replace the MCP HMAC and Grafana password before shared deployment; use workload identity and TLS in production.
- Retrieved documents and MCP outputs remain untrusted data. Never execute embedded instructions.
## Required deployment secrets

The demo and production Compose files fail closed unless SITEPILOT_DB_PASSWORD, TEMPORAL_DB_PASSWORD, SITEPILOT_MCP_HMAC_SECRET, and GRAFANA_ADMIN_PASSWORD are supplied through the environment or a non-committed .env file. The repository contains placeholders only and no usable default credential.

