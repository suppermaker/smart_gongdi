import os
from uuid import uuid4

import pytest
from conftest import activities_for
from temporalio.client import Client
from temporalio.worker import Worker

from sitepilot_agent_runtime.activities import ActivityStore
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


@pytest.mark.real_temporal
@pytest.mark.asyncio
async def test_signal_survives_worker_shutdown_and_restart():
    address = os.environ.get("TEMPORAL_ADDRESS")
    assert address, "TEMPORAL_ADDRESS is required; real-server tests fail closed"
    client = await Client.connect(address)
    task_queue = f"restart-{uuid4()}"
    value = InvestigationInput(
        CONTRACT_VERSION,
        f"task-{uuid4()}",
        "http://control-plane.invalid",
        f"idem-{uuid4()}",
        BudgetPolicy(4, 120),
        HumanQuestion("restart-q", "Continue after restart?", True),
    )
    first_store = ActivityStore()
    async with Worker(
        client,
        task_queue=task_queue,
        workflows=[InvestigationWorkflow],
        activities=activities_for(first_store),
    ):
        handle = await client.start_workflow(
            InvestigationWorkflow.run,
            value,
            id=f"restart-wf-{uuid4()}",
            task_queue=task_queue,
        )
        for _ in range(50):
            state = await handle.query(InvestigationWorkflow.current_state)
            if state.phase == WorkflowPhase.WAITING_FOR_HUMAN:
                break
            await __import__("asyncio").sleep(0.1)
        assert state.phase == WorkflowPhase.WAITING_FOR_HUMAN

    await handle.signal(
        InvestigationWorkflow.submit_human_answer,
        HumanAnswer("restart-q", "yes", True, "restart-operator"),
    )

    second_store = ActivityStore()
    async with Worker(
        client,
        task_queue=task_queue,
        workflows=[InvestigationWorkflow],
        activities=activities_for(second_store),
    ):
        result = await handle.result()
    assert result.kind == ResultKind.COMPLETED
    assert second_store.effect_counts[f"{value.idempotency_key}:human:restart-q"] == 1
