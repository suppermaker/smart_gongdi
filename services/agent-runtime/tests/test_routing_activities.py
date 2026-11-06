import pytest

from sitepilot_agent_runtime.activities import ActivityStore, InvestigationActivities
from sitepilot_agent_runtime.contracts import (
    CONTRACT_VERSION,
    ExecuteRoutedDagInput,
    LoadRoutingContextInput,
    PersistRoutingDecisionInput,
    RouteInvestigationInput,
)


@pytest.mark.asyncio
async def test_routing_activities_compile_safe_dag_and_are_idempotent():
    store = ActivityStore()
    activities = InvestigationActivities(store)
    context = await activities.load_routing_context(
        LoadRoutingContextInput(CONTRACT_VERSION, "task-1")
    )
    route = await activities.route_investigation(
        RouteInvestigationInput(
            CONTRACT_VERSION,
            context,
            "routing-policy.v1",
            "route:task-1:0:routing-policy.v1",
        )
    )
    assert route.route_disposition in {"AUTO_ROUTE", "COLLECT_EVIDENCE"}
    assert len(route.compiled_tasks) >= 2
    assert all("tenant_id" not in task for task in route.compiled_tasks)
    assert any(task["role"] == "verifier" for task in route.compiled_tasks)

    persist = PersistRoutingDecisionInput(
        CONTRACT_VERSION,
        route,
        context.task_version,
        "persist-route:task-1:0:routing-policy.v1",
    )
    assert (await activities.persist_routing_decision(persist)).applied is True
    assert (await activities.persist_routing_decision(persist)).applied is False

    execute = ExecuteRoutedDagInput(
        CONTRACT_VERSION,
        route,
        "execute-route:task-1:0",
        task_version=context.task_version,
    )
    first = await activities.execute_routed_dag(execute)
    second = await activities.execute_routed_dag(execute)
    assert first.applied is True
    assert second.applied is False
    assert first.executed_task_ids == second.executed_task_ids
    assert first.context_refs == second.context_refs
    assert len(first.context_refs) == len(first.executed_task_ids)
    assert all(item["context_digest"].startswith("sha256:") for item in first.context_refs)
