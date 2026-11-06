# SitePilot Resume Quantitative Evaluation

## Scope

This report records reproducible measurements for resume and interview use. It does not claim production deployment quality.

- Live model: `Qwen/Qwen3.8-27B-FP8`
- Served model: `smart-worksite-chat`
- Runtime: local vLLM OpenAI-compatible endpoint
- Sampling: temperature `0`, seed `7007`
- Context cases: 12 synthetic safety-investigation cases
- Routing cases: 9 synthetic event-routing cases
- Deterministic Scenario Gym: 12 variants

Artifacts:

- `artifacts/evaluations/qwen-live/results.json`
- `artifacts/evaluations/qwen-live/summary.json`
- `artifacts/evaluations/m7-deterministic/context_metrics.json`

## 1. Multi-label routing versus single-choice routing

| Metric | Single choice | Multi-label capability routing | Change |
|---|---:|---:|---:|
| Required capability recall | 41.67% | 82.41% | +40.74 pp |
| Multi-label F1 | 50.74% | 77.94% | +27.20 pp |
| Capability precision | 77.78% | 76.11% | -1.67 pp |
| Human-escalation accuracy | 77.78% | 88.89% | +11.11 pp |
| Unsafe capability rate | 0% | 0% | unchanged |
| Average latency | 465.83 ms | 821.17 ms | +355.35 ms |
| Average total tokens | 279.00 | 298.78 | +7.09% |

Interpretation: multi-label routing substantially improved investigation-capability coverage and F1 at the cost of additional latency and output tokens. This supports routing to capabilities instead of selecting only one Agent.

## 2. Counterevidence pinning

Comparison: role-scoped ContextPack versus the same policy without mandatory contradiction retention.

| Metric | Without contradiction pinning | Role ContextPack | Change |
|---|---:|---:|---:|
| Decision accuracy | 91.67% | 100% | +8.33 pp |
| Contradicting-evidence recognition | 83.33% | 100% | +16.67 pp |
| Required citation recall | 79.17% | 91.67% | +12.50 pp |
| Average prompt tokens | 227.50 | 233.58 | +2.67% |

Interpretation: retaining low-score counterevidence improved correctness and evidence coverage with a small prompt-token increase.

## 3. Role ContextPack versus Full Graph

### Live Qwen results

| Metric | Full Graph | Role ContextPack | Change |
|---|---:|---:|---:|
| Decision accuracy | 100% | 100% | unchanged |
| Required citation recall | 87.50% | 91.67% | +4.17 pp |
| Contradiction recognition | 100% | 100% | unchanged |
| Critical-conflict recognition | 100% | 100% | unchanged |
| Average input nodes | 3.33 | 2.50 | -25.00% |
| Average prompt tokens | 253.67 | 233.58 | -7.92% |
| Average latency | 993.42 ms | 980.19 ms | -1.33% |

### Deterministic context-compiler results

| Metric | Full Graph | Role ContextPack | Change |
|---|---:|---:|---:|
| Required-context recall | 100% | 100% | unchanged |
| Average estimated context tokens | 411.67 | 275.00 | -33.20% |
| Irrelevant-context rate | 26.25% | 2.78% | -23.47 pp |
| Expired-evidence leakage | 16.67% | 0% | -16.67 pp |
| Cross-tenant leakage | 0% | 0% | unchanged |
| Context replay consistency | 100% | 100% | unchanged |

Interpretation: role-scoped context reduced irrelevant input and token volume while maintaining required evidence and improving citation recall. The deterministic token estimate and actual model tokenizer measure different layers and must not be presented as the same metric.

## 4. Evidence-aware rerouting

Deterministic Scenario Gym comparison:

| Metric | Without rerouting | Evidence-aware rerouting | Change |
|---|---:|---:|---:|
| Root-cause accuracy | 66.67% | 100% | +33.33 pp |
| Required capability coverage | 86.67% | 100% | +13.33 pp |
| Rerouting recovery rate | 58.33% | 100% | +41.67 pp |
| Required evidence coverage | 91.67% | 100% | +8.33 pp |

Interpretation: rerouting after material evidence changes recovered investigation paths that the one-shot route missed.

## 5. Reliability and safety

Current complete automated suite:

- Java: 37 tests
- Agent Runtime: 37 tests
- Scenario Gym: 22 tests
- Total: 96 tests
- Failures / errors / skipped: 0 / 0 / 0

Deterministic safety metrics:

- Unsafe routing rate: 0%
- Unauthorized tool proposal rate: 0%
- Recovery success: 100%
- Root-cause accuracy: 100%
- Required evidence coverage: 100%

## Resume-ready statements

Recommended wording:

- Built multi-label capability routing; on 9 synthetic routing cases with local Qwen3.8-27B, required-capability recall improved from 41.67% to 82.41% and F1 from 50.74% to 77.94% versus single-choice routing.
- Built a Graph-Revision-scoped ContextPack; on 12 synthetic context cases, required citation recall improved from 87.50% to 91.67% while actual model prompt tokens decreased by 7.92% versus Full Graph input.
- Added mandatory counterevidence retention; decision accuracy improved from 91.67% to 100% and contradiction recognition from 83.33% to 100% versus the ablated policy.
- Added evidence-aware rerouting; deterministic root-cause accuracy improved from 66.67% to 100% and recovery rate from 58.33% to 100% versus one-shot routing.

Chinese resume wording:

- 实现多标签能力路由，在 9 个合成事件上使用本地 Qwen3.8-27B 评测，相较单标签路由将必要能力召回率由 41.67% 提升至 82.41%，多标签 F1 由 50.74% 提升至 77.94%。
- 构建基于 Graph Revision 的角色化 ContextPack，在 12 个合成上下文场景中，相较 Full Graph 将必要证据引用召回率由 87.50% 提升至 91.67%，实际 Prompt Token 降低 7.92%。
- 实现证据反馈式重路由，相较一次性路由将确定性模拟中的根因识别率由 66.67% 提升至 100%，重路由恢复率由 58.33% 提升至 100%。
- 实现证据反馈式重路由，相较一次性路由将确定性模拟中的根因识别率由 66.67% 提升至 100%，故障恢复率由 58.33% 提升至 100%。

## Limitations

- The live-model datasets are synthetic and small; report sample counts with every metric.
- Qwen measurements are local-model results, not online production traffic.
- Jev realistic live evaluation contains 18 synthetic cases, but both available egress networks are blocked by TypeSafe HTTP 451; no Jev quality metric is claimed.
- Deterministic Scenario Gym results validate architecture and safety mechanisms, not real-world model generalization.

## Jev realistic evaluation status

- Complex synthetic cases: 18
- Harness: services/scenario-gym/jev_live_eval.py
- Current status: blocked by official HTTP 451 regional availability policy
- Blocker artifact: rtifacts/evaluations/jev-live-realistic/blocker.json
