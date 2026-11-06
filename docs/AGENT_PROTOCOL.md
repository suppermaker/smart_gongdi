# Agent Protocol

## 1. Roles

### Supervisor

Owns the task objective, graph revision, budget and delegation decisions. It proposes the next bounded DAG and final action proposal.

### Investigator

Collects evidence for a scoped question. Multiple isolated instances may run concurrently. It is read-only and returns a `GraphDelta`.

### Compliance

Maps confirmed facts to current standards/rules. It must cite both business evidence and the applicable clause. It may request additional facts.

### Verifier

Performs adversarial checks. It can run at plan, conclusion and closure gates. It is read-only and may reject, approve, or request additional investigation.

## 2. Delegation contract

A delegation contains:

- unique task ID;
- role;
- objective and expected output;
- dependency task IDs;
- allowed tool profile;
- input graph revision;
- time/tool/token/cost budgets;
- acceptance criteria.

The controller rejects:

- cycles;
- missing dependencies;
- duplicate task IDs;
- excessive fan-out;
- role/tool mismatch;
- budget overflow;
- tasks based on stale graph revisions where freshness matters.

## 3. Graph delta contract

Sub-agents never write the global graph. They return:

- new hypotheses with explicit falsifiers;
- new evidence with provenance and freshness;
- support/contradiction edges;
- newly discovered questions;
- conflicts and missing information;
- proposed hypothesis status updates;
- explanation and usage metadata.

A deterministic merger validates and commits an immutable graph revision.

## 4. Information-value scheduling

Candidate tasks are ranked by a bounded heuristic:

```text
utility =
  hypothesis_discrimination
  * expected_evidence_reliability
  * urgency_weight
  - latency_cost
  - model/tool_cost
  - action_risk
  - redundancy_penalty
```

The numeric score is scheduling metadata, not a calibrated probability.

## 5. Investigation completion gate

The gate checks:

- required evidence categories;
- independent source count;
- freshness;
- unresolved critical conflicts;
- explicit counterexample search;
- regulated-action compliance assessment;
- verifier decision;
- uncertainty declaration;
- budget status.

## 6. Action protocol

Agent output uses proposals:

- `PROPOSE_RISK_CONFIRMATION`
- `PROPOSE_HUMAN_QUESTION`
- `PROPOSE_REMEDIATION`
- `PROPOSE_NOTIFICATION`
- `PROPOSE_REINSPECTION`
- `PROPOSE_CLOSURE`

The control plane decides whether a proposal is rejected, accepted as a draft, requires approval, or is executed.

Each approved action has:

- idempotency key;
- evidence prerequisites;
- expected environment effect;
- verification window;
- rollback/compensation note where applicable.

## 7. Investigation and action double loop

### Investigation loop

- update competing hypotheses;
- choose high-value evidence task(s);
- run tools/sub-agents;
- merge evidence;
- resolve conflicts;
- pass conclusion gate.

### Action loop

- propose action;
- validate/approve;
- execute once;
- wait for observation window;
- collect new environment evidence;
- verify effect;
- close or reopen investigation.

An action proposal is not task completion.

## 8. No-progress policy

A task is considered no-progress when a configurable number of revisions add no new accepted evidence, resolve no question, change no hypothesis status and produce no human escalation.

No-progress response:

1. run verifier diagnosis;
2. allow one bounded replan;
3. escalate to human or fail with a typed reason.

## 9. Output schema discipline

All role outputs are schema validated. Invalid output is retried only under a bounded policy. There is no silent JSON repair that changes semantics. Raw output is retained for diagnosis outside business state.

## Investigation routing decision

`InvestigationRoutingDecision` v1 records provider/model/policy versions, input digest, intent probabilities, capability probabilities, disposition and fact references. Capability output is restricted to `contracts/investigation-routing/intent-ontology.v1.json`.

`REQUIRED` capabilities always enter the candidate DAG. `OPTIONAL_IF_BUDGET` capabilities are selected by descending probability within fan-out. `SKIP` cannot enter the DAG. The compiler applies identity dependencies and mandatory plan/conclusion/closure verifier stages before `DagValidator` checks permissions and budgets.

The idempotency key is `(task_id, graph_revision, policy_version)`. The same key and digest returns the stored decision; a different digest returns `ROUTING_DECISION_CONFLICT`.

## ContextPack protocol

`ContextPack` v1 contains task and AgentTask scope, Graph Revision, role objective, event/risk summaries, active hypotheses, Evidence Cards, open conflicts, unresolved questions, allowed tools, budget and a Context Manifest. Role, objective and tools are resolved from the authenticated principal and persisted routing DAG, not model arguments.

Python rejects role, task, Revision, policy, prompt or tool mismatches before invoking an Agent. Context updates still occur only through GraphDelta and the authoritative graph merger.
