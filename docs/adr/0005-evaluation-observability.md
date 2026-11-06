# ADR 0005: Evaluation and operational observability

Status: Accepted (2026-10-28)

## Decision

Scenario variants remain nested under the three M0 scenario directories. YAML loading uses `safe_load`; private oracles are loaded only by scoring. Evaluation-run v2 represents Git state explicitly, including unborn repositories, and reproducibility is defined by artifact digests rather than wall-clock paths.

Operational telemetry uses OpenTelemetry API/SDK with optional OTLP export and Prometheus through Spring Actuator/Micrometer. Trace attributes are allowlisted. Business read models remain PostgreSQL projections; Temporal state is exposed only as projected task/action status. SSE carries an explicit immutable namespace into asynchronous polling. The admin console has one mutation: idempotent, versioned human approval.

## Consequences

The deterministic evaluation gate requires no paid API or GPU. Older v1 evaluation fixtures remain valid. The demo stack omits NATS and MinIO until a real runtime path needs them. Production deployments must provide durable trace storage, authentication, TLS, and coordinated PostgreSQL/Temporal backups.
