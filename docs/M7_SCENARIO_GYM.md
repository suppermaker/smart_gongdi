# M7 Scenario Gym and Evaluation

M7 keeps the three M0 scenario directories and adds four versioned variants under each (`normal` plus three adversarial variants). The suite covers identity ambiguity, conflicting and stale evidence, tool timeout, duplicate events, interruption recovery, unauthorized actions, and fake remediation. Every variant owns public assertions/faults and a private oracle; `loader.py` never includes private data in runtime context.

`evaluate.py` executes simulated typed tools, the M5 `DeterministicModelProvider`, validated dynamic DAGs, graph revisions, verifier/action decisions, and recovery behavior. It writes `manifest.json`, `metrics.json`, `trace.jsonl`, and `summary.md` under `artifacts/evaluations/m7-deterministic`. The baseline aggregates all required quality, safety, recovery, cost, token, and latency metrics. Five paired ablations are real executions, not static expected values.

Evaluation-run contract v2 records repository truth. An unborn repository is represented as `repository_state.kind=UNBORN`, `head_commit=null`, plus a deterministic working-tree digest; a repository with a valid HEAD records the real commit.

Run `make m7-test` or `make evaluate`. Re-running the same seed produces identical artifact digests because timestamps and output paths are excluded from canonical content.
