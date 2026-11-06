# M6 Action and Closure Loop

Flyway V3 and `ActionService` implement authoritative L0-L3 proposal, approval, execution, observation, closure and reopen state with optimistic versions, idempotency, audit and outbox. Agents can propose only; L2 execution requires a human approval and L3 is rejected. Closure requires post-execution, unexpired current evidence, changed environment and verifier approval.

`RemediationWorkflow` is a new Temporal workflow type, preserving M2 replay compatibility. It validates, durably waits for approval, executes an Activity, waits on a timer, collects current evidence and closes or reopens. `ReportGenerationPort`, the external HTTP adapter contract, and a fake adapter define the integration boundary without implementing a report engine.
