# SitePilot

SitePilot is a contract-aligned smart-worksite multi-agent application platform. A unified Supervisor orchestrates Knowledge, Database, Review, Report, OCR, Risk Investigation, Synthesis and Verifier agents. The governed risk specialist retains this core loop:

```text
risk signal -> competing hypotheses -> evidence tasks -> evidence merge
-> conflict resolution -> compliance assessment -> approved action
-> environment re-observation -> verification -> close or reopen
```

## Scope boundary

- No model training or fine-tuning pipeline.
- PostgreSQL through the Java control plane is authoritative for business state.
- Temporal history is authoritative for durable execution progress, retries, timers, and signals only.
- Agent output is a proposal; deterministic services and human approval decide transitions.
- Initial releases use deterministic synthetic scenarios, not real worksite data.

## Stack and repository

- `services/control-plane/`: Java 17 / Spring Boot authoritative business service.
- `services/agent-runtime/`: Python 3.13 Temporal workflow and worker.
- `services/scenario-gym/`: deterministic fixtures and fault injection.
- `contracts/`: versioned cross-language contracts.
- `infra/compose/`: local infrastructure, including PostgreSQL-backed Temporal.
- `docs/`: architecture, milestone decisions, and verification notes.

## Current status

M0-M8 are implemented: shared contracts/scenarios, authoritative control plane, durable Temporal runtime, authenticated MCP tool plane, immutable hypothesis/evidence graph with deterministic completion gate, bounded dynamic multi-agent DAGs, an approval/action/re-observation closure loop, versioned Scenario Gym evaluation, OpenTelemetry/Prometheus operations, read models/SSE, and the admin console. Graph and action writes remain Java/PostgreSQL authority; Python subagents return typed GraphDelta proposals only.

Run:

```bash
make bootstrap
make m0-test
make m1-test
make m2-test
make m3-test
make m4-test
make m5-test
make m6-test
make m7-test
make m8-test
make evaluate
make all-test
make lint
```

`make m2-test` requires Docker and fails closed if the PostgreSQL-backed Temporal stack cannot start. Temporal uses host ports 17233 (gRPC) and 18233 (UI).

See `docs/M0_CONTRACTS_AND_SCENARIOS.md` through `docs/M8_OPERATIONS.md` and ADR 0005. The reproducible seed-7007 baseline in `artifacts/evaluations/m7-deterministic/` reports root-cause accuracy 1.0, citation precision/recall 1.0/1.0, required-evidence coverage 1.0, unsafe-action rate 0.0, and recovery success 1.0.

## Development rule

Read `AGENTS.md` before changing code. Every behavior change must update its tests and relevant decision record.

M3 MCP Tool Plane: see docs/M3_MCP_TOOL_PLANE.md. Run the Java control plane on port 18080 with PORT=18080 and set SITEPILOT_MCP_HMAC_SECRET; the MCP endpoint is standard Streamable HTTP at /mcp.

## Evidence-aware intent routing

M9 adds probability-guided multi-Agent routing. A normalized event and the current hypothesis graph are classified into versioned investigation capabilities. A deterministic policy handles unknown/ambiguous input and human escalation, then compiles capabilities into a role-safe DAG. Jev is optional; all default tests and evaluations use an offline deterministic provider.

Use `make evaluate` for reproducible routing ablations. Use `make jev-smoke-test` only with an explicitly configured API key and fixed model version.

## Revision-scoped Agent context

M10 adds a control-plane Context Assembly layer. Each Agent receives a role-scoped, token-bounded ContextPack built from one Graph Revision. Critical counterevidence is pinned, expired evidence is excluded, and retries reuse a persisted Context Snapshot. Run `make m10-test` for focused verification and `make evaluate` for context ablations.

`context-policy.v2` extends this into an adaptive evidence-graph context compiler. It assigns phase-aware budgets, records a reasoning frontier and per-node selection reasons, preserves critical conflicts and counterevidence before soft relevance ranking, emits replayable FULL/DELTA snapshot lineage, and exposes immutable evidence details through a scoped read-only MCP tool. Version 1 remains available during migration; v2 is not the default until deterministic evaluation gates pass.

Context quality and token figures in `artifacts/evaluations/m7-deterministic/` are reproducible offline Scenario Gym results, not production traffic metrics. See `docs/ADR-011-ADAPTIVE-CONTEXT-COMPILER.md`.

## Contract-aligned multi-agent application platform

M11 repositions SitePilot as the multi-agent orchestration layer for the contracted smart-worksite application platform. One Supervisor routes tasks to Knowledge, Database, Review, Report, OCR, Risk Investigation, Synthesis and Verifier agents through validated DAGs. The existing HEA Graph risk system remains a specialist module.

The platform provides `/api/v1/platform/tasks`, conversations, project-isolated hybrid RAG, fail-closed database Q&A, versioned review/report/OCR jobs, standard MCP platform tools, unified telemetry and a dual-view admin console. See `docs/CONTRACT_REQUIREMENT_TRACEABILITY.md`, `docs/CONTRACT_PLATFORM_DELIVERY.md` and `docs/ADR-012-PLATFORM-MULTI-AGENT.md`.
