"""Temporal Activity entry points; all external I/O stays outside Workflow code."""

from __future__ import annotations

import hashlib
import json

from temporalio import activity
from temporalio.exceptions import ApplicationError

from sitepilot_agent_runtime.adapters import (
    ActivityStore,
    ControlPlaneAdapter,
    FakeControlPlaneAdapter,
)
from sitepilot_agent_runtime.agent_protocol import (
    AgentRequest,
    AgentResponse,
    AgentTask,
    DagExecutor,
    GraphDelta,
    Role,
    UsageBudget,
    VerificationStage,
)
from sitepilot_agent_runtime.capability_compiler import CapabilityCompiler
from sitepilot_agent_runtime.context_contracts import AgentContextInput, ContextDeltaAssembler
from sitepilot_agent_runtime.contracts import (
    CONTRACT_VERSION,
    ExecuteRoutedDagInput,
    ExecuteRoutedDagOutput,
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
    RouteInvestigationInput,
    RouteInvestigationOutput,
    RoutingContext,
    TaskContext,
)
from sitepilot_agent_runtime.decision_provider import DecisionProvider, RuleBasedDecisionProvider
from sitepilot_agent_runtime.intent_router import IntentRouter
from sitepilot_agent_runtime.routing_policy import RoutingPolicy, RoutingPolicyConfig
from sitepilot_agent_runtime.telemetry import AgentTelemetry

__all__ = ["ActivityStore", "InvestigationActivities"]


class _RoutingAgent:
    def __init__(self, role: Role) -> None:
        self.role = role

    async def run(self, request: AgentRequest) -> AgentResponse:
        if request.task.role != self.role:
            raise ApplicationError("agent role mismatch", type="ContractViolation")
        if request.context.get("role") != self.role.value:
            raise ApplicationError("context role mismatch", type="ContractViolation")
        if request.context.get("agent_task_id") != request.task.task_id:
            raise ApplicationError("context task mismatch", type="ContractViolation")
        if request.context.get("graph_revision") != request.graph_revision:
            raise ApplicationError("context revision mismatch", type="ContractViolation")
        return AgentResponse(
            GraphDelta(
                schema_version="2.0",
                delta_id=f"routing:{request.task.task_id}",
                task_id=str(request.context.get("task_id", "routing-task")),
                agent_task_id=request.task.task_id,
                base_revision=request.graph_revision,
            )
        )


class InvestigationActivities:
    def __init__(
        self,
        store: ActivityStore | None = None,
        adapter: ControlPlaneAdapter | None = None,
        telemetry: AgentTelemetry | None = None,
        decision_provider: DecisionProvider | None = None,
    ) -> None:
        self.adapter = adapter or FakeControlPlaneAdapter(store)
        self.store = (
            self.adapter.store if isinstance(self.adapter, FakeControlPlaneAdapter) else None
        )
        self.telemetry = telemetry or AgentTelemetry()
        self.decision_provider = decision_provider or RuleBasedDecisionProvider()
        self.context_deltas = ContextDeltaAssembler()

    @activity.defn
    async def load_task_context(self, value: LoadTaskContextInput) -> TaskContext:
        with self.telemetry.span(
            "agent.activity.load_task_context",
            task_id=value.task_id,
            stage="agent",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return await self.adapter.load_task_context(value)

    @activity.defn
    async def load_routing_context(self, value: LoadRoutingContextInput) -> RoutingContext:
        with self.telemetry.span(
            "agent.routing.context",
            task_id=value.task_id,
            stage="routing",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return await self.adapter.load_routing_context(value)

    @activity.defn
    async def route_investigation(self, value: RouteInvestigationInput) -> RouteInvestigationOutput:
        with self.telemetry.span(
            "agent.routing.policy",
            task_id=value.context.task_id,
            stage="routing",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            router = IntentRouter(
                self.decision_provider,
                RoutingPolicy(RoutingPolicyConfig(version=value.policy_version)),
            )
            decision = await router.route(
                task_id=value.context.task_id,
                graph_revision=value.context.graph_revision,
                state=value.context.state,
                high_risk=value.context.high_risk,
                closure_requested=value.context.closure_requested,
                identity_resolved=value.context.identity_resolved,
            )
            budget = UsageBudget(
                max_model_calls=8,
                max_tool_calls=20,
                max_cost_usd=1.0,
                max_fanout=8,
                max_concurrency=4,
                deadline_seconds=30,
            )
            tasks = CapabilityCompiler().compile(decision, budget)
            return RouteInvestigationOutput(
                CONTRACT_VERSION,
                value.context.task_id,
                value.context.graph_revision,
                decision.to_dict(),
                [_task_to_dict(task) for task in tasks],
                decision.route_disposition.value,
            )

    @activity.defn
    async def persist_routing_decision(
        self, value: PersistRoutingDecisionInput
    ) -> PersistRoutingDecisionOutput:
        with self.telemetry.span(
            "agent.routing.persist",
            task_id=value.route.task_id,
            stage="routing",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return await self.adapter.persist_routing_decision(value)

    @activity.defn
    async def execute_routed_dag(self, value: ExecuteRoutedDagInput) -> ExecuteRoutedDagOutput:
        if self.store and value.idempotency_key in self.store.effects:
            existing = self.store.effects[value.idempotency_key]
            if isinstance(existing, ExecuteRoutedDagOutput):
                return ExecuteRoutedDagOutput(
                    existing.contract_version,
                    existing.task_id,
                    existing.graph_revision,
                    existing.evidence_fingerprint,
                    existing.executed_task_ids,
                    False,
                    existing.context_refs,
                )
        with self.telemetry.span(
            "agent.routing.execute_dag",
            task_id=value.route.task_id,
            stage="routing",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            tasks = [_task_from_dict(item) for item in value.route.compiled_tasks]
            agents = {role: _RoutingAgent(role) for role in Role}
            context_refs: list[dict[str, str]] = []

            async def context_for(task: AgentTask) -> dict[str, object]:
                request = AgentContextInput(
                    task_id=value.route.task_id,
                    agent_task_id=task.task_id,
                    role=task.role.value,
                    graph_revision=value.route.graph_revision,
                    task_version=value.task_version,
                    context_policy_version=value.context_policy_version,
                    prompt_version=f"{task.role.value}.v2",
                    allowed_tools=task.allowed_tools,
                )
                with self.telemetry.span(
                    "agent.context.load",
                    task_id=value.route.task_id,
                    stage="context",
                    correlation_id=value.correlation_id,
                    traceparent=value.traceparent,
                ):
                    loaded = await self.adapter.load_agent_context(request)
                context_refs.append(
                    {
                        "agent_task_id": task.task_id,
                        "context_id": loaded.context_id,
                        "context_digest": loaded.context_digest,
                    }
                )
                return self.context_deltas.materialize(loaded)

            results = await DagExecutor(agents).execute(
                tasks,
                UsageBudget(
                    max_model_calls=8,
                    max_tool_calls=20,
                    max_cost_usd=1.0,
                    max_fanout=8,
                    max_concurrency=4,
                    deadline_seconds=30,
                ),
                value.route.graph_revision,
                {},
                context_for,
            )
            fingerprint = hashlib.sha256(
                json.dumps(
                    [result.delta.delta_id for result in results],
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode()
            ).hexdigest()
            output = ExecuteRoutedDagOutput(
                CONTRACT_VERSION,
                value.route.task_id,
                value.route.graph_revision,
                fingerprint,
                [task.task_id for task in tasks],
                True,
                sorted(context_refs, key=lambda item: item["agent_task_id"]),
            )
            if self.store:
                self.store.apply_once(value.idempotency_key, output)
            return output

    @activity.defn
    async def persist_status(self, value: PersistStatusInput) -> PersistStatusOutput:
        with self.telemetry.span(
            "agent.activity.persist_status",
            task_id=value.task_id,
            stage="workflow",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return await self.adapter.persist_status(value)

    @activity.defn
    async def record_human_answer(self, value: RecordHumanAnswerInput) -> RecordHumanAnswerOutput:
        with self.telemetry.span(
            "agent.activity.human_approval",
            task_id=value.task_id,
            stage="approval",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return await self.adapter.record_human_answer(value)

    @activity.defn
    async def finalize_investigation(
        self, value: FinalizeInvestigationInput
    ) -> FinalizeInvestigationOutput:
        with self.telemetry.span(
            "agent.activity.finalize",
            task_id=value.task_id,
            stage="closure",
            correlation_id=value.correlation_id,
            traceparent=value.traceparent,
        ):
            return await self.adapter.finalize_investigation(value)


def _task_to_dict(task: AgentTask) -> dict[str, object]:
    return {
        "task_id": task.task_id,
        "role": task.role.value,
        "objective": task.objective,
        "dependencies": list(task.dependencies),
        "allowed_tools": list(task.allowed_tools),
        "model_calls": task.model_calls,
        "tool_calls": task.tool_calls,
        "estimated_cost_usd": task.estimated_cost_usd,
        "verification_stage": task.verification_stage.value if task.verification_stage else None,
    }


def _task_from_dict(value: dict[str, object]) -> AgentTask:
    stage = value.get("verification_stage")
    return AgentTask(
        task_id=str(value["task_id"]),
        role=Role(str(value["role"])),
        objective=str(value["objective"]),
        dependencies=tuple(str(item) for item in value.get("dependencies", [])),
        allowed_tools=tuple(str(item) for item in value.get("allowed_tools", [])),
        model_calls=int(value.get("model_calls", 1)),
        tool_calls=int(value.get("tool_calls", 1)),
        estimated_cost_usd=float(value.get("estimated_cost_usd", 0.0)),
        verification_stage=VerificationStage(str(stage)) if stage else None,
    )
