from sitepilot_agent_runtime.agent_protocol import Role, UsageBudget, VerificationStage
from sitepilot_agent_runtime.capability_compiler import CapabilityCompiler
from sitepilot_agent_runtime.routing_contracts import (
    CapabilityDecision,
    CapabilityDisposition,
    InvestigationRoutingDecision,
    RouteDisposition,
)


def routing_decision(capabilities):
    return InvestigationRoutingDecision(
        "1.0",
        "route-1",
        "task-1",
        2,
        "routing-policy.v1",
        "deterministic",
        "fixture-v1",
        "sha256:" + "a" * 64,
        "equipment_local_failure",
        0.8,
        {"equipment_local_failure": 0.8, "unknown": 0.2},
        tuple(capabilities),
        0.1,
        0.2,
        1.5,
        RouteDisposition.AUTO_ROUTE,
        (),
        "2026-10-29T09:35:00Z",
    )


def capability(name, probability=0.9, disposition=CapabilityDisposition.REQUIRED):
    return CapabilityDecision(name, probability, disposition, ("event:type",))


def test_compiler_creates_parallel_investigators_then_verifier():
    tasks = CapabilityCompiler().compile(
        routing_decision(
            [
                capability("equipment_observation"),
                capability("network_diagnosis"),
                capability("maintenance_lookup"),
                capability("conclusion_verification", 1.0),
            ]
        ),
        UsageBudget(max_model_calls=6, max_tool_calls=12, max_fanout=6),
    )
    by_id = {task.task_id: task for task in tasks}
    assert by_id["route-2-equipment_observation"].dependencies == ()
    assert by_id["route-2-network_diagnosis"].dependencies == ()
    verifier = by_id["route-2-conclusion_verification"]
    assert verifier.role == Role.VERIFIER
    assert verifier.verification_stage == VerificationStage.CONCLUSION
    assert "get_evidence_detail" in verifier.allowed_tools
    assert set(verifier.dependencies) == {
        "route-2-equipment_observation",
        "route-2-network_diagnosis",
        "route-2-maintenance_lookup",
    }


def test_compiler_enforces_identity_dependency_and_budgeted_optional_tasks():
    tasks = CapabilityCompiler().compile(
        routing_decision(
            [
                capability("identity_resolution"),
                capability("certificate_verification"),
                capability(
                    "permit_validation",
                    0.7,
                    CapabilityDisposition.OPTIONAL_IF_BUDGET,
                ),
                capability("conclusion_verification", 1.0),
            ]
        ),
        UsageBudget(max_model_calls=3, max_tool_calls=8, max_fanout=3),
    )
    by_id = {task.task_id: task for task in tasks}
    assert "route-2-permit_validation" not in by_id
    assert by_id["route-2-certificate_verification"].dependencies == (
        "route-2-identity_resolution",
    )
    assert all("merge_graph_delta" not in task.allowed_tools for task in tasks)


def test_evidence_capabilities_receive_only_the_progressive_read_tool():
    evidence_tasks = CapabilityCompiler().compile(
        routing_decision([capability("evidence_validation")]),
        UsageBudget(max_model_calls=2, max_tool_calls=4, max_fanout=2),
    )
    assert evidence_tasks[0].allowed_tools == (
        "inspect-evidence-metadata",
        "list_risk_evidence",
        "get_evidence_detail",
    )
