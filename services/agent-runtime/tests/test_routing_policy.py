from datetime import UTC, datetime

import pytest

from sitepilot_agent_runtime.decision_provider import DeterministicDecisionProvider
from sitepilot_agent_runtime.intent_router import IntentRouter, sanitize_state
from sitepilot_agent_runtime.routing_contracts import ProviderDecision, RouteDisposition
from sitepilot_agent_runtime.routing_policy import RoutingPolicy


def decision(**overrides):
    values = {
        "provider": "deterministic",
        "model_version": "fixture-v1",
        "primary_intent": "equipment_local_failure",
        "intent_probabilities": {
            "equipment_local_failure": 0.72,
            "zone_connectivity_failure": 0.20,
            "unknown": 0.08,
        },
        "capability_probabilities": {
            "equipment_observation": 0.91,
            "network_diagnosis": 0.68,
        },
        "human_probability": 0.05,
        "urgency_score": 1.5,
    }
    values.update(overrides)
    return ProviderDecision(**values)


def test_policy_supports_multi_label_and_forces_verifier():
    result = RoutingPolicy().apply(decision(), ("event:type",))
    by_name = {item.capability: item for item in result.capabilities}
    assert by_name["equipment_observation"].disposition == "REQUIRED"
    assert by_name["network_diagnosis"].disposition == "OPTIONAL_IF_BUDGET"
    assert by_name["conclusion_verification"].disposition == "REQUIRED"
    assert result.route_disposition == RouteDisposition.AUTO_ROUTE


def test_policy_handles_unknown_margin_human_and_identity_dependency():
    unknown = RoutingPolicy().apply(
        decision(
            primary_intent="unknown",
            intent_probabilities={"unknown": 0.60, "equipment_local_failure": 0.40},
            capability_probabilities={},
        )
    )
    assert unknown.route_disposition == RouteDisposition.HUMAN_ESCALATION

    ambiguous = RoutingPolicy().apply(
        decision(
            intent_probabilities={
                "equipment_local_failure": 0.50,
                "zone_connectivity_failure": 0.46,
                "unknown": 0.04,
            }
        )
    )
    assert ambiguous.route_disposition == RouteDisposition.COLLECT_EVIDENCE

    human = RoutingPolicy().apply(
        decision(
            capability_probabilities={
                "certificate_verification": 0.90,
                "permit_validation": 0.90,
            },
            human_probability=0.90,
        ),
        identity_resolved=False,
    )
    assert human.route_disposition == RouteDisposition.HUMAN_CLARIFICATION
    by_name = {item.capability: item for item in human.capabilities}
    assert by_name["certificate_verification"].disposition == "SKIP"
    assert by_name["human_clarification"].disposition == "REQUIRED"


@pytest.mark.asyncio
async def test_router_is_auditable_and_redacts_sensitive_state():
    router = IntentRouter(
        DeterministicDecisionProvider(decision()),
        clock=lambda: datetime(2026, 10, 29, 9, 35, tzinfo=UTC),
    )
    result = await router.route(
        task_id="task-1",
        graph_revision=3,
        state={
            "event": {"event_type": "equipment.offline"},
            "tenant_id": "must-not-leak",
            "api_key": "secret",
            "fact_refs": ["event:type"],
        },
    )
    assert result.decision_id.startswith("route-task-1-3-")
    assert result.input_digest.startswith("sha256:")
    assert result.created_at == "2026-10-29T09:35:00+00:00"
    assert sanitize_state({"token": "x", "safe": 1}) == {"safe": 1}
