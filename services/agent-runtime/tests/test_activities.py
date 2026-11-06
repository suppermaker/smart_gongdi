import pytest

from sitepilot_agent_runtime.activities import ActivityStore, InvestigationActivities
from sitepilot_agent_runtime.contracts import CONTRACT_VERSION, PersistStatusInput


@pytest.mark.asyncio
async def test_same_idempotency_key_applies_side_effect_once():
    store = ActivityStore()
    activities = InvestigationActivities(store)
    value = PersistStatusInput(CONTRACT_VERSION, "task-1", "STARTED", "same-key")
    first = await activities.persist_status(value)
    second = await activities.persist_status(value)
    assert first.applied is True
    assert second.applied is False
    assert store.effect_counts["same-key"] == 1
