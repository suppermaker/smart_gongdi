"""M6 deterministic Temporal outer action/observation/closure loop."""

from dataclasses import dataclass
from datetime import timedelta

from temporalio import activity, workflow


@dataclass
class RemediationInput:
    task_id: str
    action_id: str
    level: str
    observation_window_seconds: int
    idempotency_key: str
    correlation_id: str | None = None
    traceparent: str | None = None


@dataclass
class RemediationResult:
    status: str
    reason: str


@dataclass
class ApprovalSignal:
    approved: bool


class RemediationActivities:
    def _telemetry(self):
        from sitepilot_agent_runtime.telemetry import AgentTelemetry

        if not hasattr(self, "telemetry"):
            self.telemetry = AgentTelemetry()
        return self.telemetry

    @activity.defn
    async def validate_proposal(self, value: RemediationInput) -> str:
        with self._telemetry().span(
            "agent.action.validate",
            task_id=value.task_id,
            stage="approval",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return "WAITING_APPROVAL" if value.level == "L2" else "VALIDATED"

    @activity.defn
    async def execute_action(self, value: RemediationInput) -> str:
        with self._telemetry().span(
            "agent.action.execute",
            task_id=value.task_id,
            stage="action",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return "EXECUTED"

    @activity.defn
    async def collect_current_evidence(self, value: RemediationInput) -> dict[str, bool]:
        with self._telemetry().span(
            "agent.action.reobserve",
            task_id=value.task_id,
            stage="evidence",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return {"current": True, "environment_changed": True}

    @activity.defn
    async def verify_closure(self, evidence: dict[str, bool]) -> bool:
        from sitepilot_agent_runtime.telemetry import AgentTelemetry

        with AgentTelemetry().span(
            "agent.action.verify_closure", task_id="remediation", stage="closure"
        ):
            return evidence.get("current", False) and evidence.get("environment_changed", False)


@workflow.defn
class RemediationWorkflow:
    def __init__(self) -> None:
        self.approval: ApprovalSignal | None = None

    @workflow.run
    async def run(self, value: RemediationInput) -> RemediationResult:
        if value.level == "L3":
            return RemediationResult("FAILED", "ACTION_LEVEL_FORBIDDEN")
        state = await workflow.execute_activity(
            RemediationActivities.validate_proposal,
            value,
            start_to_close_timeout=timedelta(seconds=10),
        )
        if state == "WAITING_APPROVAL":
            await workflow.wait_condition(lambda: self.approval is not None)
            if self.approval is None or not self.approval.approved:
                return RemediationResult("FAILED", "ACTION_APPROVAL_REQUIRED")
        await workflow.execute_activity(
            RemediationActivities.execute_action,
            value,
            start_to_close_timeout=timedelta(seconds=10),
        )
        await workflow.sleep(timedelta(seconds=value.observation_window_seconds))
        evidence = await workflow.execute_activity(
            RemediationActivities.collect_current_evidence,
            value,
            start_to_close_timeout=timedelta(seconds=10),
        )
        closed = await workflow.execute_activity(
            RemediationActivities.verify_closure,
            evidence,
            start_to_close_timeout=timedelta(seconds=10),
        )
        return RemediationResult(
            "CLOSED" if closed else "REOPENED",
            "CURRENT_EVIDENCE_VERIFIED" if closed else "REMEDIATION_NOT_PROVEN",
        )

    @workflow.signal
    async def approve(self, value: ApprovalSignal) -> None:
        self.approval = value
