"""Compile approved capabilities into a bounded, role-safe AgentTask DAG."""

from __future__ import annotations

from dataclasses import dataclass

from sitepilot_agent_runtime.agent_protocol import (
    AgentTask,
    DagValidator,
    Role,
    UsageBudget,
    VerificationStage,
)
from sitepilot_agent_runtime.routing_contracts import (
    CapabilityDecision,
    CapabilityDisposition,
    InvestigationRoutingDecision,
)


@dataclass(frozen=True)
class CapabilityRoute:
    role: Role
    objective: str
    tools: tuple[str, ...]
    dependencies: tuple[str, ...] = ()
    verification_stage: VerificationStage | None = None


CAPABILITY_ROUTES = {
    "identity_resolution": CapabilityRoute(
        Role.INVESTIGATOR,
        "Resolve the subject identity before person-specific investigation",
        ("get-identity-review",),
    ),
    "certificate_verification": CapabilityRoute(
        Role.INVESTIGATOR,
        "Verify the confirmed worker certificates",
        ("get-worker-certificates",),
        ("identity_resolution",),
    ),
    "permit_validation": CapabilityRoute(
        Role.INVESTIGATOR,
        "Verify the confirmed worker assignment to the active work permit",
        ("get-work-permit",),
        ("identity_resolution",),
    ),
    "equipment_observation": CapabilityRoute(
        Role.INVESTIGATOR,
        "Collect current equipment and nearby equipment observations",
        ("get-equipment-status", "query-nearby-equipment"),
    ),
    "equipment_diagnosis": CapabilityRoute(
        Role.INVESTIGATOR,
        "Diagnose local equipment failure",
        ("device_diagnostic",),
        ("equipment_observation",),
    ),
    "network_diagnosis": CapabilityRoute(
        Role.INVESTIGATOR,
        "Diagnose gateway, uplink and collection-path health",
        ("query-nearby-equipment", "get-gateway-status", "get-collector-health"),
    ),
    "maintenance_lookup": CapabilityRoute(
        Role.INVESTIGATOR,
        "Check authorized maintenance and outage plans",
        ("get-maintenance-plan",),
    ),
    "evidence_validation": CapabilityRoute(
        Role.INVESTIGATOR,
        "Validate evidence provenance, freshness and applicability",
        ("inspect-evidence-metadata", "list_risk_evidence", "get_evidence_detail"),
    ),
    "regulation_retrieval": CapabilityRoute(
        Role.COMPLIANCE,
        "Retrieve applicable regulation and project safety requirements",
        ("policy_lookup", "search-regulation-clauses", "search-equipment-safety-clauses"),
    ),
    "remediation_validation": CapabilityRoute(
        Role.COMPLIANCE,
        "Validate submitted remediation against acceptance criteria",
        ("search-remediation-criteria", "list_risk_evidence", "get_evidence_detail"),
        ("evidence_validation",),
    ),
    "current_observation": CapabilityRoute(
        Role.INVESTIGATOR,
        "Collect a current site observation after remediation",
        ("get-current-site-observation",),
    ),
    "plan_verification": CapabilityRoute(
        Role.VERIFIER,
        "Verify that the investigation plan covers required evidence",
        ("graph_read", "list_risk_evidence", "get_evidence_detail"),
        verification_stage=VerificationStage.PLAN,
    ),
    "conclusion_verification": CapabilityRoute(
        Role.VERIFIER,
        "Verify that the conclusion is supported by valid non-conflicting evidence",
        ("graph_read", "list_risk_evidence", "get_evidence_detail"),
        verification_stage=VerificationStage.CONCLUSION,
    ),
    "closure_verification": CapabilityRoute(
        Role.VERIFIER,
        "Verify closure using current observation and action state",
        (
            "graph_read",
            "list_risk_evidence",
            "get_evidence_detail",
            "read_action",
            "current_observation",
        ),
        ("current_observation",),
        VerificationStage.CLOSURE,
    ),
    "human_clarification": CapabilityRoute(
        Role.SUPERVISOR,
        "Ask a bounded human clarification question before continuing",
        ("request-human-identity-confirmation",),
    ),
}


class CapabilityCompiler:
    def __init__(self, validator: DagValidator | None = None) -> None:
        self.validator = validator or DagValidator()

    def compile(
        self, decision: InvestigationRoutingDecision, budget: UsageBudget
    ) -> list[AgentTask]:
        selected = self._select(decision.capabilities, budget.max_fanout)
        selected_names = {item.capability for item in selected}
        tasks: list[AgentTask] = []
        non_verifier_ids: list[str] = []
        verifier_capabilities = {
            "plan_verification",
            "conclusion_verification",
            "closure_verification",
        }

        for item in selected:
            if item.capability in verifier_capabilities:
                continue
            route = CAPABILITY_ROUTES[item.capability]
            task_id = f"route-{decision.graph_revision}-{item.capability}"
            dependencies = tuple(
                f"route-{decision.graph_revision}-{dependency}"
                for dependency in route.dependencies
                if dependency in selected_names
            )
            task = AgentTask(
                task_id=task_id,
                role=route.role,
                objective=route.objective,
                dependencies=dependencies,
                allowed_tools=route.tools,
                model_calls=1,
                tool_calls=len(route.tools),
                estimated_cost_usd=0.001,
            )
            tasks.append(task)
            non_verifier_ids.append(task_id)

        for item in selected:
            if item.capability not in verifier_capabilities:
                continue
            route = CAPABILITY_ROUTES[item.capability]
            explicit = tuple(
                f"route-{decision.graph_revision}-{dependency}"
                for dependency in route.dependencies
                if dependency in selected_names
            )
            dependencies = tuple(dict.fromkeys((*non_verifier_ids, *explicit)))
            tasks.append(
                AgentTask(
                    task_id=f"route-{decision.graph_revision}-{item.capability}",
                    role=route.role,
                    objective=route.objective,
                    dependencies=dependencies,
                    allowed_tools=route.tools,
                    model_calls=1,
                    tool_calls=len(route.tools),
                    estimated_cost_usd=0.001,
                    verification_stage=route.verification_stage,
                )
            )

        self.validator.validate(tasks, budget)
        return tasks

    @staticmethod
    def _select(
        capabilities: tuple[CapabilityDecision, ...], max_fanout: int
    ) -> list[CapabilityDecision]:
        required = [
            item for item in capabilities if item.disposition == CapabilityDisposition.REQUIRED
        ]
        optional = sorted(
            (
                item
                for item in capabilities
                if item.disposition == CapabilityDisposition.OPTIONAL_IF_BUDGET
            ),
            key=lambda item: item.probability,
            reverse=True,
        )
        if len(required) > max_fanout:
            return required
        return [*required, *optional[: max_fanout - len(required)]]
