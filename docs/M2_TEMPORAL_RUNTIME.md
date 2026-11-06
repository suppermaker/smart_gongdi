# M2 Temporal Durable Runtime

## Boundary

`services/agent-runtime` uses Python 3.13 and Temporal's Python SDK. Temporal history is authoritative only for execution progress, retries, timers, queries, and signals. The Java control plane remains authoritative for task, risk, approval, evidence, and remediation business state. M2's in-memory Activity adapter is a test/dev seam; M3 replaces it with authenticated control-plane calls without moving business authority.

## Contracts and flow

Payloads are versioned dataclasses containing only JSON-stable primitives, enums, lists, and nested dataclasses. `InvestigationInput`, `InvestigationState`, and `InvestigationResult` define workflow boundaries; separate typed inputs/outputs exist for every Activity. `HumanQuestion` and `HumanAnswer` include approval semantics.

The deterministic workflow executes:

1. `load_task_context`;
2. idempotent `persist_status`;
3. optional durable wait for `submit_human_answer`;
4. idempotent `record_human_answer`;
5. idempotent `finalize_investigation`;
6. typed completed, escalated, or failed result.

All I/O and side effects are Activities. Workflow code uses only Temporal time and durable waiting. `current_state` is a query and does not mutate history.

## Reliability policy

Activities use a 10 second start-to-close timeout, a 30 second schedule-to-close timeout bounded by the workflow deadline, and exponential retry with four attempts. `InvalidTask`, `AuthorizationDenied`, and `ContractViolation` are non-retryable application error types. Every side-effect Activity input requires a deterministic `idempotency_key`.

Budgets include `max_activity_calls` and `workflow_deadline_seconds`. Exhaustion returns `ResultKind.ESCALATED` with a stable code and never reports success. Signals sent before a worker starts or while it is stopped remain in Temporal history and are consumed after restart.

## Verification

`make m2-test` starts `infra/compose/compose.temporal.yml` on gRPC port 17233 and UI port 18233, waits for server health, and runs both WorkflowEnvironment and real-server tests. It fails closed if Docker or Temporal cannot start. Coverage includes transient Activity retry, one-time side effects, signal wait/consumption, activity budget exhaustion, replay through `Replayer`, and worker shutdown/restart with a signal sent while no worker is running.

## Workflow compatibility

This is workflow contract version 1 and the replay baseline. Future behavior changes must retain replay compatibility or use Temporal workflow versioning plus a migration note and updated saved-history replay test.
