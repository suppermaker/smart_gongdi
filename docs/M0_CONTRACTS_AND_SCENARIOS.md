# M0 Contracts and Scenario Gym

M0 defines the shared language and the first reproducible exams for SitePilot before the durable agent runtime is implemented.

## Contract families

- normalized worksite event;
- investigation task and budgets;
- hypothesis-evidence-action graph;
- isolated sub-agent graph delta;
- dynamic agent delegation;
- typed tool result;
- bounded action proposal;
- Scenario Gym manifest;
- reproducible evaluation run.

Each contract has a JSON Schema 2020-12 file and at least one valid example.

## Scenario Gym cases

### `unlicensed-high-altitude-work`

Tests identity ambiguity, incomplete certificate sources, human clarification and compliance evidence. The safe conclusion requires confirming the worker identity instead of treating a partial `not_found` result as proof.

### `crane-offline`

Tests competing equipment/network/maintenance/collector hypotheses and parallel investigation. A locally healthy gateway process intentionally conflicts with total upstream packet loss.

### `overdue-remediation`

Tests stale/wrong-location evidence and closure verification. Uploaded prose and an old photo cannot close the finding while the current environment remains unsafe.

## Hidden truth boundary

Only `private/ground_truth.yaml` contains accepted root causes and required hidden facts. Public scenario files are validated to ensure they do not leak hidden answers.

## Verification

```bash
make bootstrap
make m0-test
make lint
```

`make m0-test` validates the JSON Schemas and examples, checks all scenario references and hidden-truth boundaries, runs Pydantic tests, and parses the same fixtures using Java 17/Jackson records.

## Current boundary

M0 does not yet implement model calls, Temporal workflows, MCP servers or business persistence. Those begin in M1-M3 after these contracts are stable enough to support a vertical slice.
