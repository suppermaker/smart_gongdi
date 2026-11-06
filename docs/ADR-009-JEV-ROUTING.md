# ADR-009: Jev probability routing behind deterministic policy

## Status

Accepted for M9.

## Context

SitePilot previously selected tools through evaluator-owned static plans. That path was deterministic but could not express multi-label intent, open-set rejection, evidence-driven rerouting or auditable model confidence. Directly allowing a model to choose Agent roles or MCP tools would violate the repository's identity, permission and authority boundaries.

## Decision

Jev is an optional System One decision provider behind `DecisionProvider`. It receives a minimized structured event/graph state and answers versioned `choice`, `noul` and `score` questions. Its output is not authoritative.

`RoutingPolicy` converts probabilities into `REQUIRED`, `OPTIONAL_IF_BUDGET` and `SKIP`, handles unknown intent, confidence margin and human escalation, and applies mandatory Compliance/Verifier rules. `CapabilityCompiler` maps approved capabilities to fixed roles, tool allowlists and dependencies, then invokes `DagValidator`.

Routing decisions are persisted in PostgreSQL by `(tenant, project, task, graph_revision, policy_version)`. Temporal calls Jev only through Activities and records a patch marker for the new workflow path. The default test/demo provider is deterministic.

## Security and privacy

- Jev never receives tenant IDs, project IDs, credentials, tokens or storage locations.
- Jev cannot name arbitrary tools, change budgets, approve actions or close risks.
- Unknown or malformed output fails into bounded evidence collection or human escalation.
- API keys exist only in Agent Runtime environment variables and are never persisted or traced.
- Retrieved text is data, not instructions; prompt-injection text cannot expand capabilities.

## Failure handling

Retry only transient network, 429 and 5xx failures. Authentication, authorization and contract errors are non-retryable. A failed provider uses an already persisted decision when available, then a conservative event-type capability map, otherwise human escalation. Fallback is explicit in the decision provenance.

## Consequences

The system gains probability-guided multi-Agent routing and evidence-aware replanning without making a model authoritative. It adds an external service option and threshold calibration work. Real Jev quality must be reported separately from deterministic Scenario Gym results.
