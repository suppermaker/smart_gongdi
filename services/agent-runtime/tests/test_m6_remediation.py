from dataclasses import dataclass

import pytest
from temporalio import activity
from temporalio.worker import Replayer, Worker

from sitepilot_agent_runtime.remediation import (
    ApprovalSignal,
    RemediationActivities,
    RemediationInput,
    RemediationWorkflow,
)


@dataclass
class Fixture:
    current: bool
    changed: bool


class FakeActivities(RemediationActivities):
    def __init__(self, fixture):
        self.fixture = fixture
        self.executions = 0

    @activity.defn
    async def execute_action(self, value):
        self.executions += 1
        return "EXECUTED"

    @activity.defn
    async def collect_current_evidence(self, value):
        return {"current": self.fixture.current, "environment_changed": self.fixture.changed}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "fixture,expected",
    [
        (Fixture(False, True), "REOPENED"),
        (Fixture(True, False), "REOPENED"),
        (Fixture(True, True), "CLOSED"),
    ],
)
async def test_s3_current_evidence_and_real_change_required(temporal_env, fixture, expected):
    fake = FakeActivities(fixture)
    queue = f"m6-{expected}-{fixture.current}-{fixture.changed}"
    async with Worker(
        temporal_env.client,
        task_queue=queue,
        workflows=[RemediationWorkflow],
        activities=[
            fake.validate_proposal,
            fake.execute_action,
            fake.collect_current_evidence,
            fake.verify_closure,
        ],
    ):
        result = await temporal_env.client.execute_workflow(
            RemediationWorkflow.run,
            RemediationInput("task", "action", "L1", 0, "key"),
            id=queue,
            task_queue=queue,
        )
    assert result.status == expected
    assert fake.executions == 1


@pytest.mark.asyncio
async def test_l2_waits_for_approval_and_l3_fails(temporal_env):
    fake = FakeActivities(Fixture(True, True))
    queue = "m6-approval"
    async with Worker(
        temporal_env.client,
        task_queue=queue,
        workflows=[RemediationWorkflow],
        activities=[
            fake.validate_proposal,
            fake.execute_action,
            fake.collect_current_evidence,
            fake.verify_closure,
        ],
    ):
        handle = await temporal_env.client.start_workflow(
            RemediationWorkflow.run,
            RemediationInput("task", "action", "L2", 0, "key"),
            id=queue,
            task_queue=queue,
        )
        await handle.signal(RemediationWorkflow.approve, ApprovalSignal(True))
        assert (await handle.result()).status == "CLOSED"
        forbidden = await temporal_env.client.execute_workflow(
            RemediationWorkflow.run,
            RemediationInput("task", "forbidden", "L3", 0, "key2"),
            id="m6-l3",
            task_queue=queue,
        )
        assert forbidden.reason == "ACTION_LEVEL_FORBIDDEN"


@pytest.mark.asyncio
async def test_m6_history_replays(temporal_env):
    fake = FakeActivities(Fixture(True, True))
    queue = "m6-replay"
    async with Worker(
        temporal_env.client,
        task_queue=queue,
        workflows=[RemediationWorkflow],
        activities=[
            fake.validate_proposal,
            fake.execute_action,
            fake.collect_current_evidence,
            fake.verify_closure,
        ],
    ):
        handle = await temporal_env.client.start_workflow(
            RemediationWorkflow.run,
            RemediationInput("task", "action", "L1", 0, "replay-key"),
            id="m6-replay-history",
            task_queue=queue,
        )
        await handle.result()
        history = await handle.fetch_history()
    await Replayer(workflows=[RemediationWorkflow]).replay_workflow(history)
