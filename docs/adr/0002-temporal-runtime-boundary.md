# ADR 0002: Temporal Runtime Boundary

- Status: Accepted
- Date: 2026-10-28

## Context

SitePilot needs durable investigation execution, Activity retries, human approval waits, and interruption recovery without making Temporal a second business database.

## Decision

Place Temporal workflows and workers in `services/agent-runtime` using Python 3.13 and `temporalio`. Keep workflow payloads explicitly versioned and JSON-stable. Keep workflow code deterministic and move all I/O into typed Activities. Require idempotency keys on side-effect Activities and explicit timeout/retry/non-retryable policies. PostgreSQL behind the Java control plane remains authoritative for business state; Temporal history owns execution progress only.

Use a PostgreSQL-backed local Temporal deployment for restart tests. M2 uses a replaceable in-memory Activity adapter for deterministic tests; the subsequent tool-plane milestone supplies the authenticated Java adapter.

## Consequences

Worker restart and human signals are durable, and replay detects incompatible workflow changes. Activity adapters must enforce control-plane authority and idempotency. Contract or workflow behavior changes require a replay/versioning migration note.
