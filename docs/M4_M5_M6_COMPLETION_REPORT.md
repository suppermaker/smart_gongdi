# M4-M6 Completion Report

Date: 2026-10-28

## Delivered

### M4

- Flyway `V2__m4_graph_runtime.sql` adds immutable graph revisions, graph nodes for hypotheses/evidence/questions/conflicts/action proposals, and typed evidence edges without changing V1.
- `GraphDelta` keeps V1 compatibility and adds a materialized V2 schema requiring evidence category, task subject and expiry plus action proposals.
- `GraphMergeService` is the sole writer: row-lock/expected-revision merge, immutable revision records, reference checks, semantic idempotency, stable same-ID/different-semantics errors, and evidence source/version/time/subject/tool provenance validation.
- The deterministic completion gate checks required evidence categories, independent source count, freshness/subject, critical conflict, counterexample search, compliance, verifier approval, uncertainty and budget. Model `done` is explicitly ignored.
- S1 PostgreSQL Testcontainers tests cover normal merge, replay idempotency, missing evidence, wrong subject, expired evidence and semantic-ID attacks.

### M5

- Python defines typed Supervisor/Investigator/Compliance/Verifier agents, versioned requests, structured `GraphDelta` validation, deterministic and optional OpenAI-compatible `ModelProvider` implementations.
- `DagValidator` enforces dependencies, missing references, cycles, duplicate IDs, role/tool profiles, fanout, concurrency, deadline, model/tool/cost budgets.
- `DagExecutor` runs independent tasks concurrently and dependency levels serially; verifier responses can request more evidence.
- `NoProgressGuard` deterministically terminates repeated no-progress revisions with human escalation.
- S2 tests prove overlapping device/network/maintenance execution and verifier ordering, plus cycle, duplicate, permission, budget, failure and no-progress attacks.

### M6

- Flyway `V3__m6_action_loop.sql` and `ActionService` implement L0-L3 proposal, approval, execution, observation, close/reopen with idempotency key, optimistic version, audit and outbox.
- Agents only receive proposal tools; L2 execution requires trusted human approval and L3 is rejected.
- Closure requires evidence observed after execution, still valid, actual environment change and closure verifier approval; old photos/prose or unchanged environment reopen.
- New `RemediationWorkflow` preserves M2 history compatibility and implements validate -> durable approval wait -> Activity execution -> timer -> current evidence -> closure verifier -> close/reopen. Replay is tested.
- `ReportGenerationPort`, external HTTP adapter contract and fake adapter/test are present; no report engine was implemented.

### MCP and authority

- Added `graph_read`, `merge_graph_delta`, `propose_action`, and `read_action` to the existing standard Streamable HTTP/HMAC MCP server.
- Tool schemas contain no tenant/project/user/role/credential inputs. Principal identity remains transport-derived.
- Read tools are available to scoped roles; authoritative merge/action proposal tools are Supervisor-only. Existing MCP audit records success/rejection/failure.
- Real Python official MCP client integration now calls Java `graph_read`; Java official-client tests discover all new tools and validate identity-free schemas.

## Verification

Final `make all-test` passed on 2026-10-28 with Docker/PostgreSQL 16/Temporal and zero skipped tests.

- 45 unique tests: 24 Java, 17 agent-runtime Python, 4 scenario-gym Python.
- 111 test executions in the M0-M6 aggregate gate because milestone gates intentionally repeat lower-level Java suites.
- Final gate details: contract/scenario validation OK; Java 24/24; M2/Temporal 16 passed with only the separately-run M3 marker deselected; real M3 Java/Python 1 passed; M5 3 passed; M6 5 Python passed; focused M4/M6 Java suites passed.
- `make lint` passed for scenario-gym and agent-runtime Ruff checks.
- Testcontainers and real MCP/Temporal tests failed closed if infrastructure was unavailable; no skip-on-Docker-unavailable path was added.

## Acceptance Criteria Mapping

- Immutable graph, single merge, optimistic revision, references/idempotency/conflicts: `GraphMergeService`, V2 migration, `GraphAndActionIntegrationTest`.
- Evidence provenance/freshness/subject and deterministic completion: `GraphMergeService.completionGate`, S1 adversarial tests.
- Typed agents/provider/prompts/budgets and dynamic DAG: `agent_protocol.py`, `test_m5_agent_protocol.py`.
- Parallel S2 and adversarial DAG/no-progress behavior: timing/order and rejection tests in `test_m5_agent_protocol.py`.
- Authoritative action levels/approval/idempotency/audit/outbox: `ActionService`, V3 migration, Java integration tests.
- Temporal outer loop/replay and S3 reopen: `remediation.py`, `test_m6_remediation.py`, Java old-evidence test.
- Report boundary without engine: `ReportGenerationPort`, HTTP/fake adapters and port tests.
- MCP minimum privilege/identity boundary/audit: `McpServerConfiguration`, `McpToolService`, Java and Python MCP integration tests.

## Residual Risks

- The optional OpenAI-compatible provider is configuration-only and intentionally not exercised against a paid/live model endpoint.
- The report HTTP adapter is contract-tested without a live external report service; response authentication/retry policy belongs to deployment integration work.
- The real Python-to-Java MCP gate exercises authenticated graph reading; merge/action tool behavior is covered by Java official-client/schema/role tests and PostgreSQL HTTP integration rather than a second live Python mutation fixture.
- The repository had no tracked baseline (the pre-existing tree was entirely untracked), so Git cannot distinguish prior user files from new work; no destructive Git operation or commit was performed.
