# Architecture Decisions

This document records the initial decisions that constrain implementation. Material changes require an ADR under `docs/adr/`.

## AD-001: Agent-first product boundary

SitePilot is centered on autonomous investigation and bounded action. Event ingestion, storage, retrieval and workflow infrastructure support the agent; they are not the product definition.

## AD-002: Hypothesis-evidence-action graph

The authoritative investigation state is a structured, revisioned graph containing goals, hypotheses, evidence, questions, conflicts, constraints and proposed actions. Chat messages are not sufficient state.

Reasons:

- makes support and contradiction explicit;
- allows parallel sub-agent graph deltas;
- enables deterministic stopping gates;
- supports trace replay and evaluation;
- prevents conclusions from being hidden in prose.

## AD-003: Dynamic multi-agent DAG

Investigator, Compliance and Verifier are reusable roles, not mutually exclusive terminal routes. One task can execute them sequentially, in parallel and repeatedly.

The controller may execute:

```text
plan verifier
  -> parallel investigator instances
  -> evidence merge/conflict detection
  -> additional investigation
  -> compliance assessment
  -> conclusion verifier
  -> approved action
  -> re-observation
  -> closure verifier
```

An LLM may propose a DAG but code validates cycles, dependencies, role/tool permissions, budgets and fan-out.

## AD-004: Java authority, Python reasoning

Java owns authoritative business state and policy. Python owns agent reasoning and orchestration. Python cannot directly commit business transitions.

The boundary is contract-first over MCP/HTTP and shared JSON Schema fixtures.

## AD-005: Temporal as durable agent runtime

Temporal is used for long-running investigation tasks, child executions, human waits, retries, deadlines and post-action observation. It is not used for simple CRUD or every message.

Workflow code remains deterministic. Model/tool/database calls run as Activities.

## AD-006: MCP for least-privilege tools

Business capabilities are exposed as typed MCP tools/resources. Trusted identity, tenant, project, task and permissions are injected by the server/session context and are absent from model-controlled arguments.

## AD-007: Modular monolith first

The Java control plane begins as a modular monolith. The Python agent runtime and scenario gym are separate processes because they have different runtime/dependency boundaries. Additional service decomposition requires measured operational or scaling need.

## AD-008: PostgreSQL-first retrieval

Use PostgreSQL plus pgvector and text search for the first version. Hide retrieval behind a port so OpenSearch can be introduced after measured need. Do not deploy a vector database only for architectural appearance.

## AD-009: Synthetic deterministic data first

The initial benchmark and demo use versioned, seeded scenarios with hidden ground truth and tool simulators. This supports reproducible evaluation before real worksite integration.

## AD-010: No model training

No pretraining, fine-tuning, LoRA, PPO or training-data pipeline is in scope. Improvements come from contracts, tools, retrieval, structured state, planning, verification and evaluation.

## AD-011: Report integration, not duplication

SitePilot defines a `ReportGenerationPort` and does not build another full report lifecycle. A later MCP/HTTP adapter may send risk snapshots, metrics and evidence to the existing CryptoAgent report service.

## AD-012: Infrastructure baseline

Server environment observed at project creation:

- Java 17 is installed; project targets Java 17.
- Python 3.13 and `uv` are installed.
- Node.js 20 and Docker Compose are installed.
- Two NVIDIA H100 GPUs are available, but local large-model deployment is optional.
- Ports 9000/9001 and several common service ports are already occupied; Compose must use a documented, non-conflicting port map.

Initial suggested host ports:

- control plane: 18080
- agent runtime: 18081
- admin console: 13000
- PostgreSQL: 15432
- NATS client/monitor: 14222/18222
- Temporal: 17233
- Temporal UI: 18233
- MinIO API/console: 19010/19011
- Grafana: 13001
- Prometheus: 19090

Ports must remain configurable through environment variables.

## M9 System One routing

The investigation entry path is `normalized event + graph revision -> DecisionProvider -> RoutingPolicy -> CapabilityCompiler -> DagValidator`. Jev is the optional System One provider for typed probability decisions. The generative agents remain System Two and produce GraphDelta only. PostgreSQL stores authoritative routing records; Temporal stores durable execution progress.

Jev never selects raw tools. It scores versioned capabilities. The policy layer handles unknown intent, confidence margin, human clarification and mandatory Compliance/Verifier rules. The compiler owns role, tool and dependency mappings. A Graph Revision may trigger another route only after material evidence change and within the routing budget.

## M10 revision-scoped context assembly

The control plane assembles a role-specific `ContextPack` from an immutable Graph Revision and the persisted AgentTask. Investigator, Compliance and Verifier receive different graph slices. Critical conflicts and counterevidence are mandatory; expired evidence is excluded before model invocation. Context Snapshots are replay inputs only and never replace PostgreSQL business truth.

Temporal records `context_id` and `context_digest`, while the compact sanitized ContextPack remains in PostgreSQL. The same task/agent/revision/policy/prompt key reuses the same Snapshot during Activity retry.
