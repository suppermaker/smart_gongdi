# M4-M8 Final Completion Report

Date: 2026-10-29

## Outcome

SitePilot M4-M8 is implemented and verified on `172.18.12.6` under `/home/xidian/bijianxun/SitePilot`. The repository remains intentionally uncommitted (`UNBORN`) because the approved boundary prohibited automatic commits.

## Acceptance status

### M4 - Met

- PostgreSQL/Flyway V2 stores immutable graph revisions, graph nodes and evidence edges.
- `GraphMergeService` is the sole authoritative GraphDelta writer with expected revision, reference, semantic-idempotency and evidence validation gates.
- Completion is deterministic and ignores model-declared `done`.
- S1 normal and adversarial evidence cases pass against PostgreSQL.

### M5 - Met

- Supervisor, Investigator, Compliance and Verifier use typed protocols and role tool profiles.
- DAG validation covers dependency references, cycles, duplicate IDs, fan-out, concurrency, deadline and model/tool/cost budgets.
- Independent tasks execute concurrently; dependent tasks execute by level.
- No-progress revisions terminate with typed human escalation.
- S2 parallel and adversarial tests pass.

### M6 - Met

- Flyway V3 and `ActionService` implement L0-L3 proposals, approval, execution, observation, close/reopen, idempotency, audit and outbox.
- L2 requires human approval and L3 is forbidden.
- Closure requires current post-action evidence, actual environment change and verifier approval.
- Temporal remediation workflow performs durable approval wait, action, observation timer, re-observation and closure verification.
- Report integration is a port/adapter contract only; no duplicate report engine exists.

### M7 - Met

- Three main scenarios contain twelve versioned variants covering ambiguity, conflict, stale evidence, timeout, duplicate event, interruption recovery, unauthorized action and fake remediation.
- Public runtime context excludes private oracle data.
- Root causes are inferred from public tool observations; there is no scenario-to-answer map.
- Deterministic runner produces manifest, metrics, trace and summary artifacts and executes five paired ablations.
- Unborn Git state is represented honestly with a working-tree digest and null commit.

### M8 - Met

- Java and Python emit allowlisted OpenTelemetry spans.
- Python MCP calls inject W3C `traceparent`; Java MCP dispatch extracts the remote parent before business spans.
- Actuator/Prometheus, OTel Collector and Grafana are provisioned.
- Read APIs and SSE expose risk, graph, evidence, conflict, timeline, pending approval and result views with namespace isolation.
- Static responsive admin console is served by Spring Boot; approval is its only write action.
- Demo Compose was built and started; control plane, console, Prometheus, Grafana and Temporal health checks passed; shutdown left no ports or containers.
- Database, Temporal database, MCP signing and Grafana passwords are required external environment values with no usable repository defaults.

## Verification

Final commands passed after all fixes:

```bash
make all-test
make lint
make evaluate
```

Unique tests:

- Java: 28
- Agent runtime Python: 20
- Scenario Gym Python: 8
- Total: 56
- Failures/errors/skipped: 0/0/0

The aggregate milestone Makefile intentionally repeats lower-level suites in focused gates; unique test count is therefore lower than aggregate executions.

## Evaluation artifacts

Directory: `artifacts/evaluations/m7-deterministic`

Final artifact digests:

- working-tree digest: sha256:d43d3b5a65e5bb4481a183f81063ec54b79305c02cade5b75bbc44d003e34859

- `metrics.json`: `sha256:16ffaf20c3617ec3fc5d4efc258751515b3373b73a3b10c52868194e172d01a9`
- `trace.jsonl`: `sha256:c4b3f05ca6a12a6110fa335bc717cfd1a58fa46e2b16783ba02d037c70029d08`
- `summary.md`: `sha256:a4cabfdf4d1783cadc98b1927ffec929ffcaf9fd5c8f20dcded3a40e916a75e6`

Synthetic deterministic baseline, seed 7007:

- root-cause accuracy: 1.0
- citation precision/recall: 1.0 / 1.0
- required evidence coverage: 1.0
- conflict detection: 1.0
- unsafe action rate: 0.0
- recovery success: 1.0

Important ablation observations:

- chat history citation recall: 0.166667 vs structured graph: 1.0
- single hypothesis root-cause accuracy: 0.75 vs competing hypotheses: 1.0
- fixed pipeline root-cause accuracy: 0.333333 vs dynamic DAG: 1.0
- without verifier unsafe action rate: 0.083333 vs verifier: 0.0
- without re-observation recovery: 0.583333 vs re-observation: 1.0

These are synthetic Scenario Gym metrics, not production-site claims.

## Residual risks

- HMAC Bearer tokens are internal service credentials, not a full OAuth 2.1 workload-identity implementation.
- Real paid/live LLM providers are not part of automated acceptance; deterministic providers are the reproducible baseline.
- The report HTTP adapter is contract-tested without a live external report service.
- Invalid MCP tokens currently generate server security stack traces; traces contain no token/secret, but production logging should map these to concise structured 401/403 events.
- The repository has no initial Git commit by explicit boundary; provenance uses `UNBORN` plus working-tree digest.
