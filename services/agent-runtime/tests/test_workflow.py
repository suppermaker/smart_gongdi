from uuid import uuid4

import pytest
from conftest import TASK_QUEUE
from temporalio.worker import Replayer

from sitepilot_agent_runtime.contracts import (
    CONTRACT_VERSION,
    BudgetPolicy,
    HumanAnswer,
    HumanQuestion,
    InvestigationInput,
    ResultKind,
    WorkflowPhase,
)
from sitepilot_agent_runtime.workflow import InvestigationWorkflow


def input_for(*, question=None, max_calls=4, deadline=60, routing=False):
    suffix = str(uuid4())
    return InvestigationInput(
        CONTRACT_VERSION,
        f"task-{suffix}",
        "http://control-plane.invalid",
        f"idem-{suffix}",
        BudgetPolicy(max_calls, deadline),
        question,
        routing_enabled=routing,
        max_routing_revisions=2,
    )


@pytest.mark.asyncio
async def test_minimal_workflow_completes(runtime):
    client, store = runtime
    value = input_for(max_calls=3)
    result = await client.execute_workflow(
        InvestigationWorkflow.run, value, id=f"wf-{value.task_id}", task_queue=TASK_QUEUE
    )
    assert result.kind == ResultKind.COMPLETED
    assert result.activity_calls == 3
    assert sum(store.effect_counts.values()) == 2


@pytest.mark.asyncio
async def test_routed_workflow_persists_decision_executes_dag_and_replays(runtime):
    client, store = runtime
    value = input_for(max_calls=7, routing=True)
    handle = await client.start_workflow(
        InvestigationWorkflow.run, value, id=f"wf-{value.task_id}", task_queue=TASK_QUEUE
    )
    result = await handle.result()
    state = await handle.query(InvestigationWorkflow.current_state)
    assert result.kind == ResultKind.COMPLETED
    assert result.activity_calls == 7
    assert state.routing_decision_id is not None
    assert state.routing_revisions == 1
    route_effects = [key for key in store.effects if ":persist-route:" in key]
    execute_effects = [key for key in store.effects if ":execute-route:" in key]
    assert len(route_effects) == 1
    assert len(execute_effects) == 1
    history = await handle.fetch_history()
    await Replayer(workflows=[InvestigationWorkflow]).replay_workflow(history)


@pytest.mark.asyncio
async def test_transient_failure_retries_without_duplicate_side_effect(runtime):
    client, store = runtime
    store.transient_failures["persist_status"] = 1
    value = input_for(max_calls=3)
    result = await client.execute_workflow(
        InvestigationWorkflow.run, value, id=f"wf-{value.task_id}", task_queue=TASK_QUEUE
    )
    assert result.kind == ResultKind.COMPLETED
    assert store.effect_counts[f"{value.idempotency_key}:started"] == 1


@pytest.mark.asyncio
async def test_human_signal_is_waited_for_consumed_and_queryable(runtime):
    client, store = runtime
    question = HumanQuestion("q-1", "May remediation proceed?", True)
    value = input_for(question=question)
    handle = await client.start_workflow(
        InvestigationWorkflow.run, value, id=f"wf-{value.task_id}", task_queue=TASK_QUEUE
    )
    await handle.signal(
        InvestigationWorkflow.submit_human_answer,
        HumanAnswer("q-1", "Approved after review", True, "operator-1"),
    )
    result = await handle.result()
    state = await handle.query(InvestigationWorkflow.current_state)
    assert result.kind == ResultKind.COMPLETED
    assert state.phase == WorkflowPhase.COMPLETED
    assert state.answer is not None and state.answer.approved is True
    assert store.effect_counts[f"{value.idempotency_key}:human:q-1"] == 1


@pytest.mark.asyncio
async def test_activity_budget_exhaustion_is_typed_escalation(runtime):
    client, _ = runtime
    value = input_for(max_calls=2)
    result = await client.execute_workflow(
        InvestigationWorkflow.run, value, id=f"wf-{value.task_id}", task_queue=TASK_QUEUE
    )
    assert result.kind == ResultKind.ESCALATED
    assert result.escalation_code == "MAX_ACTIVITY_CALLS_EXCEEDED"
    assert result.activity_calls == 2


@pytest.mark.asyncio
async def test_replay_saved_history(runtime):
    client, _ = runtime
    value = input_for(max_calls=3)
    handle = await client.start_workflow(
        InvestigationWorkflow.run, value, id=f"wf-{value.task_id}", task_queue=TASK_QUEUE
    )
    await handle.result()
    history = await handle.fetch_history()
    await Replayer(workflows=[InvestigationWorkflow]).replay_workflow(history)
