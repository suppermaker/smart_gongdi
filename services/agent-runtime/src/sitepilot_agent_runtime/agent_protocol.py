"""Typed M5 protocols, model gateway, bounded DAG validation and execution."""

from __future__ import annotations

import asyncio
import json
import time
import urllib.request
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Protocol


class Role(StrEnum):
    SUPERVISOR = "supervisor"
    INVESTIGATOR = "investigator"
    COMPLIANCE = "compliance"
    VERIFIER = "verifier"


class VerificationStage(StrEnum):
    PLAN = "plan"
    CONCLUSION = "conclusion"
    CLOSURE = "closure"


@dataclass(frozen=True)
class UsageBudget:
    max_model_calls: int = 8
    max_tool_calls: int = 20
    max_cost_usd: float = 1.0
    max_fanout: int = 8
    max_concurrency: int = 4
    deadline_seconds: float = 30.0


@dataclass(frozen=True)
class AgentTask:
    task_id: str
    role: Role
    objective: str
    dependencies: tuple[str, ...] = ()
    allowed_tools: tuple[str, ...] = ()
    model_calls: int = 1
    tool_calls: int = 1
    estimated_cost_usd: float = 0.0
    verification_stage: VerificationStage | None = None


@dataclass(frozen=True)
class GraphDelta:
    schema_version: str
    delta_id: str
    task_id: str
    agent_task_id: str
    base_revision: int
    new_hypotheses: tuple[dict[str, Any], ...] = ()
    new_evidence: tuple[dict[str, Any], ...] = ()
    new_questions: tuple[dict[str, Any], ...] = ()
    new_conflicts: tuple[dict[str, Any], ...] = ()
    action_proposals: tuple[dict[str, Any], ...] = ()
    hypothesis_updates: tuple[dict[str, Any], ...] = ()
    usage: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def validate(cls, value: dict[str, Any]) -> GraphDelta:
        required = {"schema_version", "delta_id", "task_id", "agent_task_id", "base_revision"}
        missing = sorted(required - value.keys())
        if missing:
            raise ProtocolError("GRAPH_DELTA_INVALID", f"missing fields: {missing}")
        if value["schema_version"] not in {"1.0", "2.0"}:
            raise ProtocolError("GRAPH_DELTA_VERSION_UNSUPPORTED", "unsupported graph delta")
        return cls(
            **{
                key: tuple(val)
                if key.startswith("new_") or key in {"action_proposals", "hypothesis_updates"}
                else val
                for key, val in value.items()
            }
        )


@dataclass(frozen=True)
class AgentRequest:
    prompt_version: str
    task: AgentTask
    graph_revision: int
    context: dict[str, Any]
    verification_stage: VerificationStage | None = None


@dataclass(frozen=True)
class AgentResponse:
    delta: GraphDelta
    request_more_evidence: tuple[str, ...] = ()


class ModelProvider(Protocol):
    async def complete(self, prompt_version: str, payload: dict[str, Any]) -> dict[str, Any]: ...


class DeterministicModelProvider:
    def __init__(self, responses: dict[str, dict[str, Any]]) -> None:
        self.responses = responses
        self.calls: list[str] = []

    async def complete(self, prompt_version: str, payload: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(prompt_version)
        if prompt_version not in self.responses:
            raise ProtocolError("PROMPT_VERSION_UNKNOWN", prompt_version)
        return json.loads(json.dumps(self.responses[prompt_version]))


class OpenAICompatibleProvider:
    """Optional stdlib adapter; construction and tests require no API key."""

    def __init__(self, base_url: str, model: str, api_key: str | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.api_key = api_key

    async def complete(self, prompt_version: str, payload: dict[str, Any]) -> dict[str, Any]:
        body = json.dumps(
            {
                "model": self.model,
                "messages": [
                    {"role": "system", "content": prompt_version},
                    {"role": "user", "content": json.dumps(payload)},
                ],
                "response_format": {"type": "json_object"},
            }
        ).encode()
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        request = urllib.request.Request(f"{self.base_url}/chat/completions", body, headers)
        raw = await asyncio.to_thread(  # noqa: ASYNC210
            lambda: urllib.request.urlopen(request, timeout=20).read()  # noqa: ASYNC210
        )
        result = json.loads(raw)
        return json.loads(result["choices"][0]["message"]["content"])


ROLE_TOOLS = {
    Role.SUPERVISOR: {
        "graph_read",
        "merge_graph_delta",
        "propose_action",
        "read_action",
        "request-human-identity-confirmation",
        "propose-remediation-rejection",
    },
    Role.INVESTIGATOR: {
        "graph_read",
        "get-worker-profile",
        "get-worker-certificates",
        "get-work-permit",
        "get-identity-review",
        "get-equipment-status",
        "query-nearby-equipment",
        "get-gateway-status",
        "get-maintenance-plan",
        "get-collector-health",
        "get-original-finding",
        "get-remediation-submission",
        "inspect-evidence-metadata",
        "get-current-site-observation",
        "get_risk",
        "list_risk_evidence",
        "get_evidence_detail",
        "device_diagnostic",
        "network_diagnostic",
        "maintenance_lookup",
    },
    Role.COMPLIANCE: {
        "graph_read",
        "policy_lookup",
        "list_risk_evidence",
        "get_evidence_detail",
        "search-regulation-clauses",
        "search-equipment-safety-clauses",
        "search-remediation-criteria",
    },
    Role.VERIFIER: {
        "graph_read",
        "list_risk_evidence",
        "get_evidence_detail",
        "read_action",
        "current_observation",
    },
}


class DagValidator:
    def validate(self, tasks: list[AgentTask], budget: UsageBudget) -> list[list[AgentTask]]:
        if len(tasks) > budget.max_fanout:
            raise ProtocolError("DAG_FANOUT_EXCEEDED", "fanout exceeds budget")
        by_id: dict[str, AgentTask] = {}
        for task in tasks:
            if task.task_id in by_id:
                raise ProtocolError("DAG_DUPLICATE_TASK_ID", task.task_id)
            by_id[task.task_id] = task
            forbidden = set(task.allowed_tools) - ROLE_TOOLS[task.role]
            if forbidden:
                raise ProtocolError("ROLE_TOOL_PERMISSION_DENIED", ",".join(sorted(forbidden)))
            if task.verification_stage is not None and task.role != Role.VERIFIER:
                raise ProtocolError("VERIFICATION_STAGE_ROLE_INVALID", task.task_id)
        for task in tasks:
            missing = set(task.dependencies) - by_id.keys()
            if missing:
                raise ProtocolError("DAG_DEPENDENCY_MISSING", ",".join(sorted(missing)))
        if sum(t.model_calls for t in tasks) > budget.max_model_calls:
            raise ProtocolError("MODEL_BUDGET_EXCEEDED", "model budget")
        if sum(t.tool_calls for t in tasks) > budget.max_tool_calls:
            raise ProtocolError("TOOL_BUDGET_EXCEEDED", "tool budget")
        if sum(t.estimated_cost_usd for t in tasks) > budget.max_cost_usd:
            raise ProtocolError("COST_BUDGET_EXCEEDED", "cost budget")
        levels: list[list[AgentTask]] = []
        completed: set[str] = set()
        while len(completed) < len(tasks):
            ready = [
                task
                for task in tasks
                if task.task_id not in completed and set(task.dependencies) <= completed
            ]
            if not ready:
                raise ProtocolError("DAG_CYCLE", "dependency cycle")
            levels.append(ready)
            completed.update(task.task_id for task in ready)
        return levels


class Agent(Protocol):
    async def run(self, request: AgentRequest) -> AgentResponse: ...


class TypedAgent:
    def __init__(self, role: Role, provider: ModelProvider) -> None:
        self.role = role
        self.provider = provider

    async def run(self, request: AgentRequest) -> AgentResponse:
        if request.task.role != self.role:
            raise ProtocolError("AGENT_ROLE_MISMATCH", request.task.task_id)
        result = await self.provider.complete(
            request.prompt_version,
            {
                "objective": request.task.objective,
                "context": request.context,
                "stage": request.verification_stage,
            },
        )
        delta = GraphDelta.validate(result["delta"])
        if delta.agent_task_id != request.task.task_id:
            raise ProtocolError("AGENT_TASK_ID_MISMATCH", request.task.task_id)
        return AgentResponse(delta, tuple(result.get("request_more_evidence", ())))


class SupervisorAgent(TypedAgent):
    def __init__(self, provider: ModelProvider) -> None:
        super().__init__(Role.SUPERVISOR, provider)


class InvestigatorAgent(TypedAgent):
    def __init__(self, provider: ModelProvider) -> None:
        super().__init__(Role.INVESTIGATOR, provider)


class ComplianceAgent(TypedAgent):
    def __init__(self, provider: ModelProvider) -> None:
        super().__init__(Role.COMPLIANCE, provider)


class VerifierAgent(TypedAgent):
    def __init__(self, provider: ModelProvider) -> None:
        super().__init__(Role.VERIFIER, provider)


class DagExecutor:
    def __init__(self, agents: dict[Role, Agent], validator: DagValidator | None = None) -> None:
        self.agents = agents
        self.validator = validator or DagValidator()

    async def execute(
        self,
        tasks: list[AgentTask],
        budget: UsageBudget,
        graph_revision: int,
        context: dict[str, Any],
        context_provider: Callable[[AgentTask], Awaitable[dict[str, Any]]] | None = None,
    ) -> list[AgentResponse]:
        levels = self.validator.validate(tasks, budget)
        deadline = time.monotonic() + budget.deadline_seconds
        results: list[AgentResponse] = []
        semaphore = asyncio.Semaphore(budget.max_concurrency)

        async def one(task: AgentTask) -> AgentResponse:
            async with semaphore:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ProtocolError("DAG_DEADLINE_EXCEEDED", task.task_id)
                stage = (
                    task.verification_stage or VerificationStage.CONCLUSION
                    if task.role == Role.VERIFIER
                    else None
                )
                task_context = (
                    await context_provider(task) if context_provider is not None else context
                )
                prompt_version = (
                    f"{task.role}.v2" if context_provider is not None else f"{task.role}.v1"
                )
                request = AgentRequest(prompt_version, task, graph_revision, task_context, stage)
                return await asyncio.wait_for(self.agents[task.role].run(request), remaining)

        for level in levels:
            results.extend(await asyncio.gather(*(one(task) for task in level)))
        return results


class NoProgressGuard:
    def __init__(self, max_no_progress_revisions: int = 2) -> None:
        self.max_no_progress_revisions = max_no_progress_revisions
        self.last_fingerprint: str | None = None
        self.count = 0

    def record(self, fingerprint: str) -> str:
        if fingerprint == self.last_fingerprint:
            self.count += 1
        else:
            self.last_fingerprint = fingerprint
            self.count = 0
        return (
            "HUMAN_ESCALATION_REQUIRED"
            if self.count >= self.max_no_progress_revisions
            else "CONTINUE"
        )


class ProtocolError(RuntimeError):
    def __init__(self, code: str, detail: str) -> None:
        self.code = code
        super().__init__(f"{code}: {detail}")
