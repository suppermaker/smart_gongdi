# M5 Dynamic Multi-Agent DAG

The Python runtime defines typed Supervisor, Investigator, Compliance and Verifier requests/responses through `TypedAgent`; subagents return only `GraphDelta`. `ModelProvider` has deterministic and optional OpenAI-compatible implementations. `DagValidator` rejects missing dependencies, cycles, duplicate IDs, role/tool violations, fanout, model/tool/cost budget overflow; `DagExecutor` runs each dependency level concurrently and levels serially under deadline/concurrency bounds. Verifiers may request more evidence and `NoProgressGuard` deterministically escalates repeated revisions.

S2 tests prove device/network/maintenance overlap and verifier ordering, plus cycle, permission, budget, failure and no-progress attacks.
