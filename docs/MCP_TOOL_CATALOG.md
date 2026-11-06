# MCP Tool Catalog

The standard Streamable HTTP endpoint is `/mcp`. HMAC bearer identity supplies tenant, project, actor, task, role and permissions; none are model arguments. Every call is role-and-permission checked and audited.

## Platform tools

| Tool | Roles | Purpose |
|---|---|---|
| `create_platform_task` | QUERY_SUPERVISOR, SUPERVISOR | Create and persist a validated specialist DAG using the signed task id |
| `read_platform_task` | platform specialist roles, VERIFIER, SUPERVISOR | Read the signed task plan/status/result |
| `execute_platform_task` | QUERY_SUPERVISOR, SUPERVISOR | Execute the validated task using explicit specialist inputs |

The execute schema permits data-source/template/document/object references and deterministic bindings. It does not permit tenant, project, role, credential, database URL, storage root or permissions.

## Risk-governance tools

`get_project_context`, `get_risk`, `get_investigation_task`, `get_routing_context`, `record_routing_decision`, `get_routing_decision`, `get_agent_context`, `list_risk_evidence`, `get_evidence_detail`, `graph_read`, `merge_graph_delta`, `propose_action`, `read_action`, and `propose_human_question` expose the existing governed HEA workflow.

`McpIntegrationTest` verifies official-client discovery, schema identity exclusion, signed namespace isolation, stale-version rejection, role denial, prompt-injection non-escalation, audit records, and tampered/expired token rejection.
