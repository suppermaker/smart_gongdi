# ADR 0001: Initial Architecture

- Status: Accepted
- Date: 2026-10-28

## Context

SitePilot must be agent-first, complement the existing report agent, run on the available server, avoid model training, and support reproducible development before real worksite data exists.

## Decision

Use a Java 17 modular control plane, Python agent runtime, Temporal durable execution, MCP least-privilege tools, PostgreSQL-first storage/retrieval, and deterministic scenario evaluation. Represent investigation as a revisioned hypothesis-evidence-action graph and use dynamic, validated multi-agent DAGs.

## Consequences

- Cross-language contracts and tests are mandatory.
- Agent output cannot directly mutate authoritative business state.
- Temporal replay compatibility is a release concern.
- Scenario Gym is part of the product engineering baseline, not optional demo code.
- The initial implementation is deliberately narrower than the full smart-worksite contract.
