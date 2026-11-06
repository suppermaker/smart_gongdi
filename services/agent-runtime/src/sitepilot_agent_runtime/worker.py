"""Temporal worker entry point."""

import asyncio
import os

from temporalio.client import Client
from temporalio.worker import Worker

from sitepilot_agent_runtime.activities import InvestigationActivities
from sitepilot_agent_runtime.adapters import McpControlPlaneAdapter
from sitepilot_agent_runtime.decision_provider import (
    FallbackDecisionProvider,
    RuleBasedDecisionProvider,
)
from sitepilot_agent_runtime.intent_router import deterministic_safe_route
from sitepilot_agent_runtime.jev_provider import JevDecisionProvider
from sitepilot_agent_runtime.remediation import RemediationActivities, RemediationWorkflow
from sitepilot_agent_runtime.telemetry import configure_telemetry
from sitepilot_agent_runtime.workflow import InvestigationWorkflow

TASK_QUEUE = "sitepilot-investigation-v1"


class _RoleTokenProvider:
    def __init__(self, fallback: str) -> None:
        self.fallback = fallback

    def token(self, role: str | None = None) -> str:
        if role:
            value = os.getenv(f"SITEPILOT_MCP_TOKEN_{role.upper()}")
            if value:
                return value
        return self.fallback


async def _no_cached_decision(state):
    return None


def _decision_provider():
    provider = os.getenv("SITEPILOT_ROUTER_PROVIDER", "deterministic")
    if provider == "deterministic":
        return RuleBasedDecisionProvider()
    if provider != "jev":
        raise ValueError("SITEPILOT_ROUTER_PROVIDER must be deterministic or jev")
    primary = JevDecisionProvider(
        api_key=os.environ["JEV_API_KEY"],
        model=os.environ["JEV_MODEL"],
        base_url=os.getenv("JEV_BASE_URL", "https://api.typesafe.ai"),
        timeout_seconds=float(os.getenv("SITEPILOT_ROUTING_TIMEOUT_SECONDS", "5")),
    )
    return FallbackDecisionProvider(primary, _no_cached_decision, deterministic_safe_route)


def _control_plane_adapter():
    token = os.getenv("SITEPILOT_MCP_TOKEN")
    if not token:
        return None
    return McpControlPlaneAdapter(
        os.getenv("SITEPILOT_CONTROL_PLANE_URL", "http://control-plane:8080"),
        _RoleTokenProvider(token),
        int(os.getenv("SITEPILOT_EXPECTED_TASK_VERSION", "0")),
        int(os.getenv("SITEPILOT_EXPECTED_GRAPH_REVISION", "0")),
    )


async def run_worker() -> None:
    configure_telemetry()
    client = await Client.connect(os.getenv("TEMPORAL_ADDRESS", "localhost:17233"))
    activities = InvestigationActivities(
        adapter=_control_plane_adapter(),
        decision_provider=_decision_provider(),
    )
    remediation = RemediationActivities()
    worker = Worker(
        client,
        task_queue=TASK_QUEUE,
        workflows=[InvestigationWorkflow, RemediationWorkflow],
        activities=[
            activities.load_task_context,
            activities.load_routing_context,
            activities.route_investigation,
            activities.persist_routing_decision,
            activities.execute_routed_dag,
            activities.persist_status,
            activities.record_human_answer,
            activities.finalize_investigation,
            remediation.validate_proposal,
            remediation.execute_action,
            remediation.collect_current_evidence,
            remediation.verify_closure,
        ],
    )
    await worker.run()


def main() -> None:
    asyncio.run(run_worker())


if __name__ == "__main__":
    main()
