# M8 Operations, Observability, and Console

The Java control plane exposes Actuator health/Prometheus metrics and OpenTelemetry spans. Python MCP calls inject W3C 	raceparent; Java extracts it before MCP tool dispatch so cross-process spans remain in one trace. OTLP export is disabled by leaving `sitepilot.otel.otlp-endpoint` empty, which is the test default. The Python runtime emits safe activity/action spans and can export OTLP with `OTEL_EXPORTER_OTLP_ENDPOINT`. Both implementations use an allowlisted attribute set and never attach tokens, secrets, credentials, or raw payloads.

Read APIs under `/api/v1/read` provide risk list/detail, graph nodes/edges/revisions/conflicts/actions, projected workflow state, pending questions/approvals, audit timeline, final results, and an SSE state stream. SSE captures an explicit namespace object before asynchronous work, validates it against trusted request context, emits heartbeat comments, and resumes after numeric `Last-Event-ID`. The only admin-console write is approval and requires expected version plus idempotency key.

The static console is served from Spring Boot resources and needs no npm runtime. It shows risk state, investigation graph, supporting/rebutting evidence, conflicts, timeline, approvals, and final results on desktop and mobile.

`infra/compose/compose.demo.yml` runs PostgreSQL, Temporal, the control plane, agent worker, OTel Collector, Prometheus, and provisioned Grafana. NATS and MinIO are intentionally absent because this demo has no active dependency on them. See `RUNBOOK.md`, `SECURITY.md`, and `BACKUP_RESTORE.md`.

The demo requires externally supplied database, Temporal database, MCP signing, and Grafana passwords; Compose has no usable defaults.
