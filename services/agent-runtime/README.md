# Agent Runtime

Python 3.13 Temporal worker for durable SitePilot investigations. The Java control plane remains authoritative for business state; this service only owns durable execution progress.

## Run

```bash
uv sync --dev
TEMPORAL_ADDRESS=localhost:17233 uv run python -m sitepilot_agent_runtime.worker
```

The M2 workflow loads authoritative task context, records an idempotent started status, optionally waits durably for a typed human answer/approval signal, records that answer idempotently, and finalizes a proposal for control-plane review. Query `current_state` exposes its typed progress.

## Test

```bash
make -C ../.. m2-test
```

The gate starts the real PostgreSQL-backed Temporal Compose stack, runs WorkflowEnvironment tests plus the restart/signal test against the real server, and fails if Docker or Temporal is unavailable.
