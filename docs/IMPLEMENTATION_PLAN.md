# SitePilot Implementation Plan

## 1. Objective

Build an agent-first smart-worksite platform that can autonomously investigate a risk signal, maintain competing hypotheses, collect and validate evidence, match applicable rules, propose bounded actions, wait for approval, re-observe the environment, and prove whether remediation succeeded.

The first release uses deterministic synthetic worksite scenarios. It does not train or fine-tune models.

## 2. Primary demo scenarios

### S1. Unlicensed high-altitude work

- Signal: a worker starts high-altitude work.
- Hidden causes include expired certificate, missing synchronization, identity mismatch and false visual association.
- Required behavior: investigate identity and certificates, find applicable standard, distinguish missing record from confirmed absence, request human input when identity is ambiguous, propose suspension only after evidence and approval.

### S2. Crane offline investigation

- Signal: a crane remains offline.
- Competing causes: device fault, zone network fault, planned maintenance, collector failure, decommissioned asset.
- Required behavior: create parallel evidence tasks, merge observations, detect contradictory heartbeats, rank hypotheses, propose a safe action, and re-observe after action.

### S3. Overdue safety remediation

- Signal: an inspection finding passes its deadline.
- Required behavior: collect original finding and assignments, determine whether evidence was submitted, escalate according to policy, evaluate remediation evidence, and reject closure if the real environment has not changed.

## 3. Architecture

```text
Signal/Event API or Scenario Gym
            |
            v
Java Control Plane ---- PostgreSQL / MinIO
     |      |                  |
     |      +---- NATS --------+
     |
     +---- MCP Server (trusted identity context)
                    |
                    v
          Python Agent Runtime
                    |
          Temporal Agent Workflow
                    |
       +------------+-------------+
       |            |             |
 Investigator   Compliance     Verifier
       +------------+-------------+
                    |
          Hypothesis-Evidence-
              Action Graph
```

### Control plane responsibilities

- authoritative tenant/project/risk/remediation state;
- authorization and trusted execution context;
- evidence registry and content digests;
- typed MCP tools/resources;
- state transition and approval gates;
- outbox/event publication;
- audit records.

### Agent runtime responsibilities

- initial hypothesis generation;
- information-value-based evidence task selection;
- dynamic task DAG and dependency management;
- role-scoped sub-agent execution;
- graph-delta merge proposals;
- conflict/uncertainty management;
- compliance assessment and action proposal;
- verifier calls at plan, conclusion and closure gates.

### Temporal responsibilities

- durable task progress;
- parallel child investigations;
- activity timeout/retry;
- budget and deadline handling;
- human Signals/Updates;
- pause/resume and recovery;
- wait then re-observe after an approved action.

## 4. Core domain model

### InvestigationTask

- task ID, goal, namespace, risk reference;
- status and current graph revision;
- budget policy and deadline;
- workflow/run IDs;
- pending approval or human question.

### Hypothesis

- statement and category;
- prior/rank score, current status;
- support and contradiction evidence edges;
- unresolved questions;
- provenance and revision.

### Evidence

- source and immutable reference;
- exact field/quote/object digest;
- observed/ingested time and freshness;
- reliability and completeness semantics;
- supporting/contradicting graph edges;
- producing tool call and agent task.

### Question

- information gap;
- hypotheses it distinguishes;
- candidate tools and expected value;
- blocking/non-blocking status;
- human answer contract when required.

### ProposedAction

- action type and level L0-L3;
- expected effect and verification criteria;
- evidence prerequisites;
- approval policy;
- execution idempotency key.

### GraphRevision

- immutable parent revision;
- accepted graph delta;
- merge result/conflicts;
- decision and actor provenance.

## 5. Agent algorithm

1. Normalize the signal and load trusted task context.
2. Generate diverse candidate hypotheses with explicit falsifiers.
3. Run plan verification to detect missing obvious alternatives, duplicate tasks and forbidden tools.
4. Score candidate evidence tasks by expected hypothesis reduction, evidence reliability, latency, cost and action risk.
5. Generate a bounded DAG; execute independent tasks in parallel.
6. Merge typed graph deltas under optimistic graph revision control.
7. Detect conflicts, stale evidence and completeness limitations.
8. Continue until deterministic stopping criteria pass or budget/human escalation is reached.
9. Run conclusion verification with adversarial counterexample search.
10. Produce an action proposal with prerequisites and verification criteria.
11. After approval and execution, wait for the relevant environment window and re-observe.
12. Run closure verification. Close only if deterministic business gates pass; otherwise reopen investigation.

## 6. Deterministic stopping criteria

Investigation may finish only when:

- a leading conclusion has required independent evidence coverage;
- no unresolved critical conflict remains;
- mandatory counterexample search ran;
- evidence freshness is valid;
- mandatory compliance assessment is present for regulated actions;
- uncertainty is explicitly represented;
- budgets are within limits;
- a verifier approves the conclusion contract.

Model output `done=true` is never sufficient.

## 7. Milestones

### M0 - Repository and contracts

Deliverables:

- repository rules and ADR template;
- event, task, graph, evidence, graph-delta and action schemas;
- Java/Python generated-model strategy;
- Compose plan and port map;
- three scenario specifications.

Exit criteria:

- schemas validate examples;
- Java and Python can deserialize the same fixtures;
- namespace fields are absent from model-controlled tool inputs.

### M1 - Control-plane vertical slice

Deliverables:

- Spring Boot modular monolith;
- project/risk/evidence/remediation modules;
- PostgreSQL migrations;
- optimistic state versions;
- audit and transactional outbox;
- basic REST APIs.

Exit criteria:

- create a simulated risk and evidence record;
- invalid transition fails closed;
- duplicate source event does not duplicate risk.

### M2 - Temporal durable skeleton

Deliverables:

- Temporal dev deployment;
- InvestigationWorkflow and typed Activities;
- retry/timeout/idempotency policies;
- human question/approval Signals;
- replay test baseline.

Exit criteria:

- Worker restart resumes the same task;
- repeated Activity execution creates no duplicate side effect;
- workflow can wait for and consume human input.

### M3 - MCP tool plane

Deliverables:

- Java MCP server;
- trusted session/task context;
- role-scoped read tools and proposal tools;
- audit log for every tool call;
- namespace and permission tests.

Exit criteria:

- model cannot choose tenant/project/user;
- Investigator cannot invoke approval/state-transition tools;
- cross-project access tests fail closed.

### M4 - Hypothesis-evidence graph runtime

Deliverables:

- graph models and immutable revisions;
- evidence edge validation;
- graph-delta merge with conflict handling;
- stopping gate;
- single-agent investigation loop.

Exit criteria:

- S1 completes with structured hypotheses and citations;
- unsupported conclusion is blocked;
- stale evidence invalidates closure.

### M5 - Dynamic multi-agent DAG

Deliverables:

- bounded DAG planner;
- cycle and dependency validation;
- parallel Investigator child tasks;
- Compliance and Verifier agents;
- graph delta aggregation;
- per-role tool profiles and budgets.

Exit criteria:

- S2 runs equipment/network/maintenance investigations in parallel;
- Compliance waits for required facts;
- verifier can request additional investigation;
- repeated no-progress loop terminates.

### M6 - Action and closure loop

Deliverables:

- action risk levels and approval policy;
- proposal -> validation -> approval -> execution flow;
- post-action observation timers;
- closure verifier and reopen behavior;
- report integration port contract.

Exit criteria:

- S3 cannot close from uploaded prose alone;
- failed remediation reopens the task;
- duplicate approval/execution does not duplicate action.

### M7 - Scenario Gym and evaluation

Deliverables:

- hidden-ground-truth scenario format;
- deterministic tool simulators;
- duplicate/out-of-order/error/timeout injection;
- evaluation runner and metrics artifacts;
- baseline single-agent and ablation configurations.

Exit criteria:

- all three scenarios have normal and adversarial variants;
- evaluation is reproducible from one command;
- metrics distinguish correctness, safety, efficiency and recovery.

### M8 - Observability and operator UI

Deliverables:

- OpenTelemetry traces across Java/Python/Temporal/MCP;
- Prometheus/Grafana dashboards;
- minimal risk/task/graph/evidence UI;
- SSE progress stream;
- deployment/runbook/security docs.

Exit criteria:

- one trace links signal, workflow, agent, tool, evidence, approval and closure;
- operator can inspect why a hypothesis won/lost;
- no secret or sensitive raw payload appears in traces.

## 8. Evaluation plan

### Correctness

- root-cause accuracy;
- risk severity accuracy;
- closure decision accuracy;
- compliance applicability accuracy.

### Evidence quality

- citation precision/recall;
- required evidence coverage;
- stale/incorrect subject evidence rate;
- unsupported conclusion rate;
- conflict detection recall.

### Agent behavior

- necessary tool coverage;
- unnecessary tool-call rate;
- hypothesis diversity and falsifier coverage;
- average graph revisions and steps;
- premature termination and loop rate;
- human escalation precision.

### Safety and reliability

- forbidden tool attempt block rate;
- cross-project isolation rate;
- duplicate side effects;
- interruption recovery success;
- malformed output rejection;
- timeout and budget handling accuracy.

### Cost

- model calls/tokens/cost per successful task;
- parallel versus sequential latency;
- small-model routing savings;
- rule/validator savings versus all-LLM execution.

## 9. Initial ablations

Compare:

1. chat-history-only state vs structured hypothesis-evidence graph;
2. first-plausible-hypothesis vs competing hypotheses;
3. model-selected tools vs information-value scoring;
4. fixed role pipeline vs dynamic DAG;
5. no verifier vs conclusion verifier;
6. advice-only completion vs post-action re-observation.

## 10. Production readiness gates

Before any external deployment:

- threat model and data classification completed;
- namespace/permission test suite passes;
- Temporal replay suite passes;
- backup/restore and evidence retention tested;
- model-provider failure/timeout degradation tested;
- workload and cost limits configured;
- audit and approval records are exportable;
- all resume claims are backed by reproducible metrics.
