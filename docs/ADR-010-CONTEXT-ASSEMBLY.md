# ADR-010: Revision-scoped role context assembly

## Status

Accepted for M10.

## Context

The HEA Graph is the authoritative investigation memory, but passing the complete graph to every Agent creates irrelevant context, expired-evidence leakage, role contamination, high token use and non-repeatable retries. Chat history and model summaries cannot provide stable evidence provenance or Revision consistency.

## Decision

SitePilot builds a typed `ContextPack` from an immutable Graph Revision. The Java control plane resolves the authenticated AgentTask from the persisted routing DAG, queries the relevant graph substructure, pins critical conflicts and supporting/contradicting evidence, excludes expired evidence, applies a versioned token budget, and stores a non-authoritative Context Snapshot plus Manifest.

The model cannot supply role, objective, capabilities, tools, tenant or project. These values come from the authenticated MCP principal and persisted DAG. Python validates Context scope and rejects tool expansion before Agent execution.

Temporal records only Context IDs and digests. Activity retries reuse the same Snapshot key `(task, agent_task, revision, context_policy, prompt_version)` rather than rebuilding from a newer graph.

## Consequences

- Agent inputs are role-scoped, auditable and replayable.
- Critical counterevidence cannot be silently removed by budget trimming.
- Context Snapshot is diagnostic model input, not a new business authority.
- PostgreSQL remains the only required storage system; no vector database, Redis or graph database is introduced.
- Policy changes require a new Context Policy version and evaluation artifacts.
