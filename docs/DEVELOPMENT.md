# Local and Server Development Baseline

## Observed server environment

The project was initialized for `/home/xidian/bijianxun/SitePilot` on server `172.18.12.6`.

Available at initialization:

- Java 17
- Maven
- Python 3.13
- uv
- Node.js 20
- Docker 29 and Docker Compose 2.40
- two NVIDIA H100 GPUs

## Runtime policy

- Target Java 17 until the server toolchain is deliberately upgraded.
- Use `uv` for Python environment and lock management.
- Use Maven Wrapper after Spring Boot bootstrap.
- All service ports are environment-configurable.
- Do not bind new services to already occupied ports such as 9000/9001.
- GPU use is optional; no core test may require a GPU.

## Initial service ports

```text
SITEPILOT_CONTROL_PORT=18080
SITEPILOT_AGENT_PORT=18081
SITEPILOT_UI_PORT=13000
SITEPILOT_POSTGRES_PORT=15432
SITEPILOT_NATS_PORT=14222
SITEPILOT_NATS_MONITOR_PORT=18222
SITEPILOT_TEMPORAL_PORT=17233
SITEPILOT_TEMPORAL_UI_PORT=18233
SITEPILOT_MINIO_PORT=19010
SITEPILOT_MINIO_CONSOLE_PORT=19011
SITEPILOT_PROMETHEUS_PORT=19090
SITEPILOT_GRAFANA_PORT=13001
```

## Intended development commands

These commands become active as milestones add implementations:

```bash
make bootstrap
make infra-up
make test
make contract-test
make scenario-eval
make lint
make infra-down
```

## Dependency policy

Do not add every planned infrastructure component on day one. The order is:

1. PostgreSQL + contracts;
2. Java/Python vertical slice;
3. Temporal;
4. MCP;
5. NATS and outbox/event ingress;
6. MinIO evidence objects;
7. observability stack;
8. optional local embedding/reranker services.

This keeps failures attributable and avoids an infrastructure-only project.

## M9 router configuration

The default `SITEPILOT_ROUTER_PROVIDER=deterministic` requires no network or key. To run the explicit synthetic Jev check, set a fixed `JEV_MODEL` and `JEV_API_KEY`, then run `make jev-smoke-test`. This target is intentionally excluded from `all-test`.

Jev calls occur only in Temporal Activities. `JEV_API_KEY` must not appear in prompts, tool arguments, traces, routing records or evaluation artifacts. For live PostgreSQL persistence through MCP, inject a short-lived Supervisor workload token with `SITEPILOT_MCP_TOKEN`; leaving it blank keeps the local deterministic adapter.

## M10 context assembly

Use `make m10-test` for focused ContextPack, MCP, Temporal and Scenario Gym checks. Live multi-role MCP execution requires separate short-lived workload tokens in `SITEPILOT_MCP_TOKEN_INVESTIGATOR`, `SITEPILOT_MCP_TOKEN_COMPLIANCE`, `SITEPILOT_MCP_TOKEN_VERIFIER` and `SITEPILOT_MCP_TOKEN_SUPERVISOR`. Tokens remain environment-only.

Context Policy changes require a new version instead of mutating `context-policy.v1`, followed by replay and context-ablation evaluation.
