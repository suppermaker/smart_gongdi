# Scenario Gym and Evaluation Specification

## Purpose

The Scenario Gym provides reproducible, hidden-ground-truth tasks for developing and evaluating SitePilot without real worksite data.

## Scenario package

Each scenario contains:

```text
scenario.yaml              visible setup and event sequence
private/ground_truth.yaml  hidden cause and accepted outcomes
tools/                     deterministic tool responses
faults.yaml                 optional failures, delay, duplicate or stale data
assertions.yaml             deterministic business assertions
```

The runtime under test must never receive `private/ground_truth.yaml`.

## Required scenario variants

For every primary scenario provide:

1. normal success;
2. ambiguous identity or subject;
3. conflicting evidence;
4. stale evidence;
5. missing-but-not-complete record source;
6. tool timeout/transient failure;
7. duplicated event/tool response;
8. interruption and resume;
9. unsafe action temptation;
10. remediation that looks complete in prose but does not change environment state.

## Baselines

- direct answer from initial signal;
- single agent with chat-history-only state;
- single agent with structured graph;
- dynamic multi-agent DAG;
- dynamic DAG without verifier;
- dynamic DAG without post-action observation.

## Metrics

### Outcome

- root-cause exact/acceptable-set accuracy;
- correct risk classification;
- correct action or escalation;
- closure decision accuracy.

### Evidence

- citation precision and recall;
- subject/time/source correctness;
- required category coverage;
- unsupported claim count;
- conflict detection and resolution.

### Process

- tool calls and useful-tool ratio;
- graph revisions;
- elapsed time and parallel speedup;
- model tokens/cost;
- human questions and escalation precision;
- no-progress termination.

### Safety/reliability

- forbidden action attempts blocked;
- namespace isolation violations;
- duplicate side effects;
- recovery after worker/process interruption;
- stale evidence rejection;
- malformed output rejection.

## Reproducibility

Every run records:

- scenario/version/seed;
- repository commit;
- model/provider/specification;
- prompt versions;
- tool contract versions;
- runtime configuration and budgets;
- graph revisions and tool events;
- metric output and artifact digests.

Resume or README claims may only reference stored evaluation runs.

## M9 routing evaluation

The default baseline uses the deterministic `DecisionProvider`, never hidden truth or scenario IDs. `TOOL_PLANS` remains only as the `static_routing` ablation. Additional ablations cover single-choice, multi-label, no rerouting and evidence-aware rerouting.

Reported routing metrics include Top-1/Top-3 intent recall, multi-label F1, required capability coverage, tool precision, unsafe/unauthorized routing rate, tool calls to root cause, rerouting recovery, fallback rate and Brier score. Deterministic-provider metrics and real Jev smoke results must be labeled separately; the smoke test validates connectivity and contract shape, not business accuracy.

## M10 context evaluation

Twelve deterministic context cases cover irrelevant evidence, critical conflicts, expired evidence, identity ordering, role isolation, budget trimming, prompt injection, cross-tenant access and closure verification. Ablations compare full graph, recent history, role context, no contradiction pinning, no freshness filter and no role scoping.

Metrics include required-context recall, critical-conflict recall, contradicting-evidence recall, expired-evidence leakage, irrelevant-context rate, estimated input tokens, replay consistency, cross-tenant leakage and false-closure rate. Results are written to `context_metrics.json` by `make evaluate`.
