# Runbook

## Start and verify

1. Copy `.env.example` to `.env` and replace demo secrets.
2. Run `make demo-up`.
3. Check `http://localhost:18080/actuator/health`, Prometheus on `19090`, Grafana on `13001`, and Temporal UI on `18233`.
4. The admin console is served at `http://localhost:18080/`; set browser local-storage keys `tenant`, `project`, and `actor` for the target namespace.

## Stop and clean

Run `make demo-down`. For a destructive local reset only, run `docker compose -f infra/compose/compose.demo.yml down -v`. Test gates use traps and must leave ports 15433, 17233, 18080, 18233, 13000, 19090, and 13001 free.

## Troubleshooting

- Control plane unhealthy: inspect `docker compose -f infra/compose/compose.demo.yml logs control-plane postgres`.
- Worker disconnected: verify Temporal health and `TEMPORAL_ADDRESS=temporal:7233`.
- Missing metrics: confirm `/actuator/prometheus` and the Prometheus target page.
- Missing traces: confirm collector logs and both OTLP endpoint environment variables.
