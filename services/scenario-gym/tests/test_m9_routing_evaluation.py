from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
import yaml
from sitepilot_agent_runtime.decision_provider import DeterministicDecisionProvider
from sitepilot_agent_runtime.intent_router import IntentRouter, deterministic_safe_route
from sitepilot_agent_runtime.routing_contracts import ProviderDecision

ROOT = Path(__file__).resolve().parents[1]


def provider_for(event: dict) -> ProviderDecision:
    return deterministic_safe_route({"event": event}) or ProviderDecision(
        "deterministic",
        "event-map-v1",
        "unknown",
        {"unknown": 1.0},
        {},
        1.0,
        1.0,
    )


@pytest.mark.parametrize(
    "case",
    yaml.safe_load((ROOT / "routing_cases.yaml").read_text(encoding="utf-8"))["cases"],
    ids=lambda case: case["id"],
)
def test_public_routing_cases_are_safe_and_open_set(case):
    event = {"event_type": case["event_type"], "title": case["title"], "attributes": {}}
    router = IntentRouter(DeterministicDecisionProvider(provider_for(event)))
    decision = asyncio.run(
        router.route(task_id=f"task-{case['id']}", graph_revision=0, state={"event": event})
    )
    if expected := case.get("expected_intent"):
        assert decision.primary_intent == expected
    if expected := case.get("expected_disposition"):
        assert decision.route_disposition == expected
    selected = {item.capability for item in decision.capabilities if item.disposition != "SKIP"}
    assert set(case.get("expected_capabilities", ())) <= selected
    if forbidden := case.get("forbidden_capability"):
        assert forbidden not in selected
    assert all(item.capability != "merge_graph_delta" for item in decision.capabilities)
