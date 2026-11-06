import asyncio
import time

import pytest

from sitepilot_agent_runtime.agent_protocol import (
    AgentResponse,
    AgentTask,
    DagExecutor,
    DagValidator,
    GraphDelta,
    NoProgressGuard,
    ProtocolError,
    Role,
    UsageBudget,
    VerificationStage,
)


class TimedAgent:
    def __init__(self, events, delay=0.12, fail=False):
        self.events = events
        self.delay = delay
        self.fail = fail

    async def run(self, request):
        self.events.append((request.task.task_id, "start", time.monotonic()))
        await asyncio.sleep(self.delay)
        if self.fail:
            raise ProtocolError("AGENT_FAILED", request.task.task_id)
        self.events.append((request.task.task_id, "end", time.monotonic()))
        delta = GraphDelta(
            "2.0",
            f"delta:{request.task.task_id}",
            "parent",
            request.task.task_id,
            request.graph_revision,
        )
        more = ("network packet capture",) if request.task.role == Role.VERIFIER else ()
        return AgentResponse(delta, more)


@pytest.mark.asyncio
async def test_s2_parallel_device_network_maintenance_then_verifier():
    events = []
    agents = {role: TimedAgent(events) for role in Role}
    tasks = [
        AgentTask("device", Role.INVESTIGATOR, "device", allowed_tools=("device_diagnostic",)),
        AgentTask("network", Role.INVESTIGATOR, "network", allowed_tools=("network_diagnostic",)),
        AgentTask(
            "maintenance", Role.INVESTIGATOR, "maintenance", allowed_tools=("maintenance_lookup",)
        ),
        AgentTask(
            "verify", Role.VERIFIER, "verify", ("device", "network", "maintenance"), ("graph_read",)
        ),
    ]
    started = time.monotonic()
    results = await DagExecutor(agents).execute(
        tasks, UsageBudget(max_model_calls=4, max_tool_calls=4), 3, {}
    )
    elapsed = time.monotonic() - started
    assert elapsed < 0.40
    starts = {name: at for name, state, at in events if state == "start"}
    ends = {name: at for name, state, at in events if state == "end"}
    assert max(starts[name] for name in ("device", "network", "maintenance")) < min(
        ends[name] for name in ("device", "network", "maintenance")
    )
    assert starts["verify"] >= max(ends[name] for name in ("device", "network", "maintenance"))
    assert results[-1].request_more_evidence == ("network packet capture",)


def test_dag_rejects_cycle_duplicate_permission_and_budgets():
    validator = DagValidator()
    with pytest.raises(ProtocolError, match="DAG_CYCLE"):
        validator.validate(
            [
                AgentTask("a", Role.INVESTIGATOR, "a", ("b",)),
                AgentTask("b", Role.INVESTIGATOR, "b", ("a",)),
            ],
            UsageBudget(),
        )
    with pytest.raises(ProtocolError, match="DAG_DUPLICATE_TASK_ID"):
        validator.validate(
            [AgentTask("a", Role.INVESTIGATOR, "a"), AgentTask("a", Role.INVESTIGATOR, "b")],
            UsageBudget(),
        )
    with pytest.raises(ProtocolError, match="ROLE_TOOL_PERMISSION_DENIED"):
        validator.validate(
            [AgentTask("a", Role.INVESTIGATOR, "a", allowed_tools=("merge_graph_delta",))],
            UsageBudget(),
        )
    with pytest.raises(ProtocolError, match="MODEL_BUDGET_EXCEEDED"):
        validator.validate(
            [AgentTask("a", Role.INVESTIGATOR, "a", model_calls=2)], UsageBudget(max_model_calls=1)
        )
    with pytest.raises(ProtocolError, match="VERIFICATION_STAGE_ROLE_INVALID"):
        validator.validate(
            [
                AgentTask(
                    "a",
                    Role.INVESTIGATOR,
                    "a",
                    verification_stage=VerificationStage.PLAN,
                )
            ],
            UsageBudget(),
        )


@pytest.mark.asyncio
async def test_failure_propagates_and_no_progress_escalates():
    with pytest.raises(ProtocolError, match="AGENT_FAILED"):
        await DagExecutor({Role.INVESTIGATOR: TimedAgent([], fail=True)}).execute(
            [AgentTask("a", Role.INVESTIGATOR, "a")], UsageBudget(), 0, {}
        )
    guard = NoProgressGuard(2)
    assert guard.record("same") == "CONTINUE"
    assert guard.record("same") == "CONTINUE"
    assert guard.record("same") == "HUMAN_ESCALATION_REQUIRED"


@pytest.mark.asyncio
async def test_verifier_executes_plan_conclusion_and_closure_stages():
    stages = []

    class StageAgent(TimedAgent):
        async def run(self, request):
            stages.append(request.verification_stage)
            return await super().run(request)

    tasks = [
        AgentTask(
            f"verify-{stage.value}",
            Role.VERIFIER,
            stage.value,
            allowed_tools=("graph_read",),
            verification_stage=stage,
        )
        for stage in VerificationStage
    ]
    await DagExecutor({Role.VERIFIER: StageAgent([], delay=0)}).execute(
        tasks,
        UsageBudget(max_model_calls=3, max_tool_calls=3, max_concurrency=3),
        1,
        {},
    )
    assert set(stages) == set(VerificationStage)


@pytest.mark.asyncio
async def test_dag_uses_role_scoped_context_provider_per_task():
    observed = {}

    class ContextAgent(TimedAgent):
        async def run(self, request):
            observed[request.task.task_id] = {
                "context": request.context,
                "prompt_version": request.prompt_version,
            }
            return await super().run(request)

    async def context_provider(task):
        return {
            "schema_version": "1.0",
            "context_id": f"ctx-{task.task_id}",
            "task_id": "parent",
            "agent_task_id": task.task_id,
            "graph_revision": 2,
            "role": task.role.value,
            "objective": task.objective,
            "allowed_tools": list(task.allowed_tools),
        }

    tasks = [
        AgentTask("network", Role.INVESTIGATOR, "network", allowed_tools=("network_diagnostic",)),
        AgentTask("compliance", Role.COMPLIANCE, "policy", allowed_tools=("policy_lookup",)),
    ]
    agents = {
        Role.INVESTIGATOR: ContextAgent([], delay=0),
        Role.COMPLIANCE: ContextAgent([], delay=0),
    }
    await DagExecutor(agents).execute(
        tasks,
        UsageBudget(max_model_calls=2, max_tool_calls=2),
        2,
        {},
        context_provider,
    )
    assert observed["network"]["context"]["role"] == "investigator"
    assert observed["compliance"]["context"]["role"] == "compliance"
    assert observed["network"]["prompt_version"] == "investigator.v2"
    assert observed["compliance"]["prompt_version"] == "compliance.v2"
