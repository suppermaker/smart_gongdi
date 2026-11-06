"""Control-plane adapters used only by Temporal Activities."""

from collections import defaultdict
from dataclasses import dataclass, field
from typing import Protocol

import httpx2
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from sitepilot_agent_runtime.context_contracts import AgentContextInput, AgentContextOutput
from sitepilot_agent_runtime.contracts import (
    CONTRACT_VERSION,
    FinalizeInvestigationInput,
    FinalizeInvestigationOutput,
    LoadRoutingContextInput,
    LoadTaskContextInput,
    PersistRoutingDecisionInput,
    PersistRoutingDecisionOutput,
    PersistStatusInput,
    PersistStatusOutput,
    RecordHumanAnswerInput,
    RecordHumanAnswerOutput,
    RoutingContext,
    TaskContext,
)
from sitepilot_agent_runtime.telemetry import AgentTelemetry, current_trace_headers


class TokenProvider(Protocol):
    def token(self, role: str | None = None) -> str: ...


class ControlPlaneAdapter(Protocol):
    async def load_agent_context(self, value: AgentContextInput) -> AgentContextOutput: ...
    async def load_task_context(self, value: LoadTaskContextInput) -> TaskContext: ...
    async def load_routing_context(self, value: LoadRoutingContextInput) -> RoutingContext: ...
    async def persist_routing_decision(
        self, value: PersistRoutingDecisionInput
    ) -> PersistRoutingDecisionOutput: ...
    async def persist_status(self, value: PersistStatusInput) -> PersistStatusOutput: ...
    async def record_human_answer(
        self, value: RecordHumanAnswerInput
    ) -> RecordHumanAnswerOutput: ...
    async def finalize_investigation(
        self, value: FinalizeInvestigationInput
    ) -> FinalizeInvestigationOutput: ...


@dataclass
class ActivityStore:
    effects: dict[str, object] = field(default_factory=dict)
    effect_counts: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    transient_failures: dict[str, int] = field(default_factory=dict)

    def apply_once(self, key: str, value: object) -> bool:
        if key in self.effects:
            return False
        self.effects[key] = value
        self.effect_counts[key] += 1
        return True


class FakeControlPlaneAdapter:
    def __init__(self, store: ActivityStore | None = None) -> None:
        self.store = store or ActivityStore()

    def _maybe_fail(self, name: str) -> None:
        from temporalio.exceptions import ApplicationError

        remaining = self.store.transient_failures.get(name, 0)
        if remaining > 0:
            self.store.transient_failures[name] = remaining - 1
            raise ApplicationError(
                "transient control-plane failure", type="ControlPlaneUnavailable"
            )

    async def load_agent_context(self, value: AgentContextInput) -> AgentContextOutput:
        import hashlib
        import json

        key = (
            f"context:{value.task_id}:{value.agent_task_id}:{value.graph_revision}:"
            f"{value.context_policy_version}:{value.prompt_version}"
        )
        digest = "sha256:" + hashlib.sha256(key.encode()).hexdigest()
        pack = {
            "schema_version": "1.0",
            "context_id": f"ctx-{digest[7:19]}",
            "task_id": value.task_id,
            "agent_task_id": value.agent_task_id,
            "graph_revision": value.graph_revision,
            "role": value.role,
            "verification_stage": None,
            "objective": f"Execute {value.agent_task_id}",
            "event_summary": {"event_type": "equipment.offline"},
            "risk_summary": {"risk_ref": f"risk:{value.task_id}", "status": "OPEN"},
            "active_hypotheses": [],
            "evidence_cards": [],
            "open_conflicts": [],
            "unresolved_questions": [],
            "action_context": [],
            "allowed_capabilities": [value.agent_task_id.rsplit("-", 1)[-1]],
            "allowed_tools": list(value.allowed_tools),
            "budget": {
                "max_model_calls": 1,
                "max_tool_calls": len(value.allowed_tools),
                "max_context_tokens": 4000,
            },
            "manifest": {
                "context_policy_version": value.context_policy_version,
                "prompt_version": value.prompt_version,
                "included_node_ids": [],
                "excluded_counts": {"expired": 0, "irrelevant": 0, "budget_trimmed": 0},
                "estimated_tokens": 128,
                "context_digest": digest,
            },
        }
        applied = self.store.apply_once(key, json.loads(json.dumps(pack)))
        return AgentContextOutput(
            pack["context_id"], digest, value.graph_revision, 128, pack, applied
        )

    async def load_task_context(self, value: LoadTaskContextInput) -> TaskContext:
        self._maybe_fail("load_task_context")
        return TaskContext(CONTRACT_VERSION, value.task_id, "OPEN", f"risk:{value.task_id}")

    async def load_routing_context(self, value: LoadRoutingContextInput) -> RoutingContext:
        self._maybe_fail("load_routing_context")
        return RoutingContext(
            CONTRACT_VERSION,
            value.task_id,
            0,
            0,
            {
                "event": {
                    "event_type": "equipment.offline",
                    "subject_type": "tower-crane",
                    "attributes": {"offline_duration_seconds": 300, "zone": "zone-a"},
                },
                "graph": {"revision": 0, "open_conflicts": 0},
                "fact_refs": ["event:event_type", "event:attributes", "graph:revision"],
            },
        )

    async def persist_routing_decision(
        self, value: PersistRoutingDecisionInput
    ) -> PersistRoutingDecisionOutput:
        self._maybe_fail("persist_routing_decision")
        decision_id = str(value.route.decision["decision_id"])
        return PersistRoutingDecisionOutput(
            CONTRACT_VERSION,
            value.route.task_id,
            decision_id,
            self.store.apply_once(value.idempotency_key, value),
        )

    async def persist_status(self, value: PersistStatusInput) -> PersistStatusOutput:
        self._maybe_fail("persist_status")
        return PersistStatusOutput(
            CONTRACT_VERSION,
            value.task_id,
            value.status,
            self.store.apply_once(value.idempotency_key, value),
        )

    async def record_human_answer(self, value: RecordHumanAnswerInput) -> RecordHumanAnswerOutput:
        self._maybe_fail("record_human_answer")
        return RecordHumanAnswerOutput(
            CONTRACT_VERSION, value.task_id, self.store.apply_once(value.idempotency_key, value)
        )

    async def finalize_investigation(
        self, value: FinalizeInvestigationInput
    ) -> FinalizeInvestigationOutput:
        self._maybe_fail("finalize_investigation")
        return FinalizeInvestigationOutput(
            CONTRACT_VERSION,
            value.task_id,
            "READY_FOR_CONTROL_PLANE_REVIEW",
            self.store.apply_once(value.idempotency_key, value),
        )


class McpControlPlaneAdapter:
    def __init__(
        self,
        base_url: str,
        token_provider: TokenProvider,
        expected_task_version: int,
        expected_graph_revision: int,
        timeout_seconds: float = 10.0,
    ) -> None:
        self.base_url = base_url.rstrip("/") + "/mcp"
        self.token_provider = token_provider
        self.expected_task_version = expected_task_version
        self.expected_graph_revision = expected_graph_revision
        self.timeout_seconds = timeout_seconds
        self.telemetry = AgentTelemetry()

    async def load_agent_context(self, value: AgentContextInput) -> AgentContextOutput:
        result = await self._call(
            "get_agent_context",
            {
                "agentTaskId": value.agent_task_id,
                "contextPolicyVersion": value.context_policy_version,
                "promptVersion": value.prompt_version,
                "expectedTaskVersion": value.task_version,
                "expectedGraphRevision": value.graph_revision,
            },
            value.task_id,
            value.role,
        )
        return AgentContextOutput.validate(result, value)

    async def load_task_context(self, value: LoadTaskContextInput) -> TaskContext:
        result = await self._call(
            "get_investigation_task",
            self._versions(),
            value.task_id,
        )
        return TaskContext(
            CONTRACT_VERSION,
            str(result["taskId"]),
            str(result["status"]),
            str(result["riskRef"]),
        )

    async def load_routing_context(self, value: LoadRoutingContextInput) -> RoutingContext:
        result = await self._call("get_routing_context", self._versions(), value.task_id)
        task = result["task"]
        assert isinstance(task, dict)
        risk = result["risk"]
        assert isinstance(risk, dict)
        return RoutingContext(
            CONTRACT_VERSION,
            value.task_id,
            int(task["version"]),
            int(task["graphRevision"]),
            dict(result),
            high_risk=str(risk.get("status", "")) == "CRITICAL",
        )

    async def persist_routing_decision(
        self, value: PersistRoutingDecisionInput
    ) -> PersistRoutingDecisionOutput:
        result = await self._call(
            "record_routing_decision",
            {
                "decision": value.route.decision,
                "compiledDag": {"tasks": value.route.compiled_tasks},
                "expectedTaskVersion": value.task_version,
                "expectedGraphRevision": value.route.graph_revision,
            },
            value.route.task_id,
        )
        return PersistRoutingDecisionOutput(
            CONTRACT_VERSION,
            value.route.task_id,
            str(value.route.decision["decision_id"]),
            bool(result["applied"]),
        )

    async def persist_status(self, value: PersistStatusInput) -> PersistStatusOutput:
        return PersistStatusOutput(CONTRACT_VERSION, value.task_id, value.status, False)

    async def record_human_answer(self, value: RecordHumanAnswerInput) -> RecordHumanAnswerOutput:
        return RecordHumanAnswerOutput(CONTRACT_VERSION, value.task_id, False)

    async def finalize_investigation(
        self, value: FinalizeInvestigationInput
    ) -> FinalizeInvestigationOutput:
        return FinalizeInvestigationOutput(
            CONTRACT_VERSION, value.task_id, "READY_FOR_CONTROL_PLANE_REVIEW", False
        )

    def _versions(self) -> dict[str, object]:
        return {
            "expectedTaskVersion": self.expected_task_version,
            "expectedGraphRevision": self.expected_graph_revision,
        }

    def _token(self, role: str | None) -> str:
        try:
            return self.token_provider.token(role)
        except TypeError:
            return self.token_provider.token()  # type: ignore[call-arg]

    async def _call(
        self,
        tool: str,
        arguments: dict[str, object],
        task_id: str = "mcp-task",
        role: str | None = None,
    ) -> dict[str, object]:
        with self.telemetry.span("agent.mcp.tool", task_id=task_id, stage="tool", tool=tool):
            headers = {
                "Authorization": f"Bearer {self._token(role)}",
                **current_trace_headers(),
            }
            timeout = httpx2.Timeout(self.timeout_seconds)
            async with httpx2.AsyncClient(headers=headers, timeout=timeout) as client:
                async with streamable_http_client(self.base_url, http_client=client) as streams:
                    async with ClientSession(streams[0], streams[1]) as session:
                        await session.initialize()
                        result = await session.call_tool(tool, arguments)
                        if result.is_error or not isinstance(result.structured_content, dict):
                            raise RuntimeError(f"MCP tool failed: {tool}")
                        return result.structured_content
