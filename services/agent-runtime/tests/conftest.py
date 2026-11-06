from collections.abc import AsyncIterator

import pytest_asyncio
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from sitepilot_agent_runtime.activities import ActivityStore, InvestigationActivities
from sitepilot_agent_runtime.workflow import InvestigationWorkflow

TASK_QUEUE = "m2-test-queue"


@pytest_asyncio.fixture
async def temporal_env() -> AsyncIterator[WorkflowEnvironment]:
    async with await WorkflowEnvironment.start_time_skipping() as environment:
        yield environment


@pytest_asyncio.fixture
async def runtime(temporal_env: WorkflowEnvironment):
    store = ActivityStore()
    activities = InvestigationActivities(store)
    worker = Worker(
        temporal_env.client,
        task_queue=TASK_QUEUE,
        workflows=[InvestigationWorkflow],
        activities=activities_for_instance(activities),
    )
    async with worker:
        yield temporal_env.client, store


def activities_for_instance(activities: InvestigationActivities):
    return [
        activities.load_task_context,
        activities.load_routing_context,
        activities.route_investigation,
        activities.persist_routing_decision,
        activities.execute_routed_dag,
        activities.persist_status,
        activities.record_human_answer,
        activities.finalize_investigation,
    ]


def activities_for(store: ActivityStore):
    return activities_for_instance(InvestigationActivities(store))
