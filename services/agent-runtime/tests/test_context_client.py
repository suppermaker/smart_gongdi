import pytest

from sitepilot_agent_runtime.activities import ActivityStore, InvestigationActivities
from sitepilot_agent_runtime.context_contracts import AgentContextInput


@pytest.mark.asyncio
async def test_fake_context_snapshot_is_role_scoped_and_idempotent():
    store = ActivityStore()
    activities = InvestigationActivities(store)
    request = AgentContextInput(
        "task-1",
        "route-0-network_diagnosis",
        "investigator",
        0,
        0,
        "context-policy.v1",
        "investigator.v2",
        ("get-gateway-status",),
    )
    first = await activities.adapter.load_agent_context(request)
    second = await activities.adapter.load_agent_context(request)
    assert first.applied is True
    assert second.applied is False
    assert first.context_digest == second.context_digest
    assert first.context_pack["role"] == "investigator"
    assert first.context_pack["allowed_tools"] == ["get-gateway-status"]
