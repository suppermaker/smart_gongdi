# ADR 0004: Single graph merge and authoritative action loop

## Status
Accepted

## Decision
PostgreSQL remains authoritative. All GraphDelta writes pass through one revision-locked Java service; subagents have no table access. Action proposals are separate from approvals/execution, L2 requires trusted human approval, L3 is forbidden, and closure requires current post-action evidence. Temporal owns orchestration/timers only.

## Consequences
Graph and action events are immutable/audited and retries are idempotent. New workflow behavior is introduced as `RemediationWorkflow` rather than changing the M2 workflow history contract.
