# Compose Infrastructure

M2 adds a dedicated PostgreSQL-backed Temporal development stack:

```bash
docker compose -f infra/compose/compose.temporal.yml up -d --wait
docker compose -f infra/compose/compose.temporal.yml down
```

Temporal gRPC binds to `17233` and the UI to `18233`. The named PostgreSQL volume preserves workflow histories across container restarts; use `down -v` only when intentionally deleting local histories.
