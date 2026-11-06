# Admin Console

Dependency-free operator console, served as static files (no npm, no bundler, no build step).
Spring Boot serves its own copy from `services/control-plane/src/main/resources/static/`; for standalone
static review run `python3 apps/admin-console/serve.py` (port 13000, override with `PORT`).

## Views

1. **Risk investigation** (default, `#risk`): active risks, investigation graph, support/rebuttal evidence,
   conflicts, audit timeline, pending approvals, final result. Approval is the only write action here.
2. **Contract platform** (`#platform`): create a platform task, open an existing task by id, inspect the
   server-classified intent, plan/DAG declaration, status and version, execute the task with the
   intent-specific parameters, and read the result — citations (knowledge/report), database rows,
   review findings, generated report artifact, OCR fields, risk-investigation handoff, synthesis and
   verification.

## API surface used (unchanged, no backend edits)

| Call | Purpose |
| --- | --- |
| `GET /api/v1/read/risks`, `/risks/{id}`, `/risks/{id}/task-graph`, `/risks/{id}/audit-timeline`, `/risks/{id}/final-result`, `/pending` | risk view read models |
| `POST /api/v1/read/actions/{actionId}/approval` | approval (expected version + idempotency key) |
| `GET /api/v1/read/stream` | SSE state stream, resumes with `Last-Event-ID` |
| `POST /api/v1/platform/tasks` | create task (`taskId`, `userInput`, `requestedOutput`, `attachmentRefs`, `idempotencyKey`) |
| `GET /api/v1/platform/tasks/{taskId}` | task read model (`intent`, `plan`, `status`, `result`, `version`) |
| `POST /api/v1/platform/tasks/{taskId}/execute` | execute with intent-specific parameters |
| `GET /api/v1/reports/jobs/{jobId}/artifact` | report download (fetched with identity headers, saved as blob) |

Identity and namespace travel only in `X-SitePilot-Tenant`, `X-SitePilot-Project`, `X-SitePilot-Actor`
plus `X-Correlation-ID`. The console never sends tenant/project/actor/role inside a request body.

## Known constraints

- The control plane exposes **no platform task list endpoint**, so tasks created here are remembered in
  `localStorage` per tenant/project and can also be opened by id.
- `GET /api/v1/platform/tasks/{taskId}` returns the plan as an orchestration declaration
  (`schemaVersion`, `taskId`, `intent`, `orchestration`, `status`). The console renders `plan.nodes`
  and `plan.edges` when the control plane starts returning them, and says so explicitly until then.
- Knowledge answers are non-authoritative and are always shown with their citations.
- Execution parameters are passed through verbatim; missing server-side templates, data sources or
  documents surface as the control plane error code.

## Layout

Desktop: two-column rail + workspace, cards in a two-column grid. Below 960px the rail becomes a
horizontally scrolling list and cards stack; below 560px forms, actions and key/value blocks go
single-column. Focus rings, `aria-selected` tabs with arrow-key support, live regions for status
messages, and a `prefers-reduced-motion` block are built in.
