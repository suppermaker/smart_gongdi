import os
from dataclasses import fields

import pytest
from temporalio.client import Client
from temporalio.worker import Worker

from sitepilot_agent_runtime.activities import InvestigationActivities
from sitepilot_agent_runtime.adapters import McpControlPlaneAdapter
from sitepilot_agent_runtime.contracts import (
    CONTRACT_VERSION,
    BudgetPolicy,
    InvestigationInput,
    LoadTaskContextInput,
    ResultKind,
)
from sitepilot_agent_runtime.workflow import InvestigationWorkflow


class EnvironmentTokenProvider:
    def token(self) -> str:
        value = os.environ.get("M3_MCP_TOKEN")
        assert value, "M3_MCP_TOKEN is required"
        return value


@pytest.mark.m3_integration
@pytest.mark.asyncio
async def test_python_official_client_and_temporal_activity_use_real_java_mcp():
    base_url = os.environ.get("M3_CONTROL_PLANE_URL")
    temporal_address = os.environ.get("TEMPORAL_ADDRESS")
    assert base_url, "M3_CONTROL_PLANE_URL is required"
    assert temporal_address, "TEMPORAL_ADDRESS is required"

    adapter = McpControlPlaneAdapter(base_url, EnvironmentTokenProvider(), 0, 0)
    context = await adapter.load_task_context(
        LoadTaskContextInput(CONTRACT_VERSION, "task-m3", base_url)
    )
    assert context.task_id == "task-m3"
    assert context.risk_reference and context.risk_reference.startswith("risk-")
    graph = await adapter._call(
        "graph_read", {"expectedTaskVersion": 0, "expectedGraphRevision": 0}
    )
    assert graph["taskId"] == "task-m3"
    assert graph["revision"] == 0

    activities = InvestigationActivities(adapter=adapter)
    client = await Client.connect(temporal_address)
    task_queue = "sitepilot-m3-integration"
    async with Worker(
        client,
        task_queue=task_queue,
        workflows=[InvestigationWorkflow],
        activities=[
            activities.load_task_context,
            activities.persist_status,
            activities.record_human_answer,
            activities.finalize_investigation,
        ],
    ):
        result = await client.execute_workflow(
            InvestigationWorkflow.run,
            InvestigationInput(
                CONTRACT_VERSION,
                "task-m3",
                base_url,
                "m3-real",
                BudgetPolicy(3, 60),
            ),
            id="sitepilot-m3-real-mcp",
            task_queue=task_queue,
        )
    assert result.kind == ResultKind.COMPLETED


def test_workflow_contract_contains_no_token_or_secret_fields():
    names = {field.name.lower() for field in fields(InvestigationInput)}
    assert not names.intersection({"token", "secret", "credential", "authorization"})
