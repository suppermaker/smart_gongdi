# ADR-011: Adaptive evidence-graph context compiler

## Status

Accepted.

## Context

ADR-010 introduced revision-scoped, role-scoped ContextPack snapshots. The first policy reduced irrelevant context and pinned counterevidence, but every graph revision still produced a full pack, token allocation was static, compact evidence could not be expanded through a scoped tool, and snapshot lineage was implicit.

A risk-investigation Agent must not optimize context only for semantic similarity. Low-scoring counterevidence, critical conflicts and closure prerequisites may invalidate an otherwise plausible conclusion. Retries must also observe the exact same context instead of rebuilding against a newer graph revision.

## Decision

`context-policy.v2` compiles a typed ContextPack from one immutable graph revision and keeps `context-policy.v1` available during migration.

The compiler:

1. resolves an investigation phase: exploration, conflict resolution, conclusion or closure;
2. slices the graph by authenticated Agent role and persisted capability;
3. applies hard priority to critical-conflict evidence, counterevidence and closure prerequisites before soft relevance scoring;
4. records a reasoning frontier, per-node selection reasons and token allocation;
5. emits stable evidence handles whose detail can be retrieved only through `get_evidence_detail`;
6. links snapshots by parent ID, digest and graph revision;
7. emits DELTA snapshots after a FULL snapshot and creates a new FULL checkpoint after three deltas.

The Agent Runtime validates the v2 contract, scope, tool allowlist and parent digest. It materializes a DELTA only when the verified parent exists in its Activity-local cache. Missing parents, digest mismatch and excessive chains fail closed.

## Authority and security

- PostgreSQL graph and business records remain authoritative. Context snapshots are diagnostic model inputs.
- Tenant, project, task, role and permissions come from the authenticated MCP principal, never tool arguments.
- `get_evidence_detail` accepts only an opaque evidence ID plus optimistic task/revision versions.
- The tool returns evidence only from the authenticated task and requested graph revision.
- Context handles cannot expand the DAG tool allowlist. Capability compilation must grant the read tool explicitly.
- Expired evidence is excluded. Critical evidence that exceeds the budget raises `CONTEXT_BUDGET_INSUFFICIENT`; it is never silently trimmed.

## Replay and failure behavior

Temporal records context IDs and digests, not mutable context content. Activity retries reuse the persisted snapshot key. DELTA materialization verifies the parent digest before merging cards and removes expired or deleted IDs deterministically.

Provider or Activity restart may lose the in-memory parent cache. A DELTA then fails with `CONTEXT_PARENT_MISSING`; the controller must request a FULL checkpoint rather than silently rebuilding against newer state. This preserves deterministic failure semantics.

## Evaluation

Scenario Gym evaluates required-context recall, critical-conflict recall, counterevidence recall, leakage, false closure, replay consistency, irrelevant-context rate, FULL input tokens and DELTA-to-FULL token ratio. Ablations disable contradiction pinning, stage budgeting, delta snapshots and progressive loading independently.

All reported values are deterministic offline evaluation results. They are not production traffic metrics.

## Consequences

The model receives smaller, role-specific and more adversarially useful context with explicit provenance and replay lineage. The design adds contract and snapshot complexity, requires parent-cache management in the Runtime, and keeps v1 during one migration cycle.
