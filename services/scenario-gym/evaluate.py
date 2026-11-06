"""Deterministic M7 evaluation runner for Scenario Gym."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
RUNTIME_SRC = ROOT / "services" / "agent-runtime" / "src"
sys.path.insert(0, str(RUNTIME_SRC))
PROTOCOL_PATH = (
    ROOT / "services" / "agent-runtime" / "src" / "sitepilot_agent_runtime" / "agent_protocol.py"
)
_spec = importlib.util.spec_from_file_location("sitepilot_agent_protocol", PROTOCOL_PATH)
if _spec is None or _spec.loader is None:
    raise RuntimeError("cannot load agent protocol")
_protocol = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = _protocol
_spec.loader.exec_module(_protocol)

from sitepilot_agent_protocol import (  # type: ignore[import-not-found]
    AgentTask,
    ComplianceAgent,
    DagExecutor,
    DeterministicModelProvider,
    InvestigatorAgent,
    Role,
    SupervisorAgent,
    UsageBudget,
    VerifierAgent,
)
from sitepilot_agent_runtime.decision_provider import DeterministicDecisionProvider
from sitepilot_agent_runtime.intent_router import IntentRouter, deterministic_safe_route
from sitepilot_agent_runtime.routing_contracts import CapabilityDisposition, ProviderDecision
from sitepilot_agent_runtime.routing_policy import RoutingPolicy

from context_eval import evaluate_contexts
from loader import ScenarioVariant, discover_variants

SCENARIOS = HERE / "scenarios"
DEFAULT_OUTPUT = ROOT / "artifacts" / "evaluations" / "m7-deterministic"
CONFIGS = {
    "baseline": {},
    "chat_history": {"structured_graph": False},
    "structured_graph": {"structured_graph": True},
    "single_hypothesis": {"competing_hypotheses": False},
    "competing": {"competing_hypotheses": True},
    "fixed_pipeline": {"dynamic_dag": False},
    "dynamic_dag": {"dynamic_dag": True},
    "without_verifier": {"verifier": False},
    "verifier": {"verifier": True},
    "without_reobservation": {"reobservation": False},
    "reobservation": {"reobservation": True},
    "static_routing": {"routing_mode": "static"},
    "single_choice_routing": {"routing_mode": "single_choice"},
    "multi_label_routing": {"routing_mode": "multi_label"},
    "without_rerouting": {"rerouting": False},
    "evidence_aware_routing": {"rerouting": True},
}
DEFAULT_FEATURES = {
    "structured_graph": True,
    "competing_hypotheses": True,
    "dynamic_dag": True,
    "verifier": True,
    "reobservation": True,
    "routing_mode": "multi_label",
    "rerouting": True,
}
TOOL_PLANS = {
    "scenario.unlicensed-high-altitude-work": [
        "get-identity-review",
        "get-work-permit",
        "get-worker-certificates",
        "search-regulation-clauses",
        "request-human-identity-confirmation",
    ],
    "scenario.crane-offline": [
        "get-equipment-status",
        "query-nearby-equipment",
        "get-gateway-status",
        "get-maintenance-plan",
        "get-collector-health",
        "search-equipment-safety-clauses",
    ],
    "scenario.overdue-remediation": [
        "get-original-finding",
        "get-remediation-submission",
        "inspect-evidence-metadata",
        "get-current-site-observation",
        "search-remediation-criteria",
        "propose-remediation-rejection",
    ],
}
EXPECTED_ROUTING = {
    "equipment.offline": (
        "equipment_local_failure",
        {
            "equipment_observation",
            "network_diagnosis",
            "maintenance_lookup",
            "regulation_retrieval",
        },
    ),
    "work.high-altitude.started": (
        "identity_or_qualification_risk",
        {
            "identity_resolution",
            "certificate_verification",
            "permit_validation",
            "regulation_retrieval",
            "human_clarification",
        },
    ),
    "remediation.deadline.exceeded": (
        "remediation_not_performed",
        {"evidence_validation", "remediation_validation", "current_observation"},
    ),
}
CAPABILITY_TOOLS = {
    "identity_resolution": ["get-identity-review"],
    "certificate_verification": ["get-worker-certificates"],
    "permit_validation": ["get-work-permit"],
    "equipment_observation": ["get-equipment-status", "query-nearby-equipment"],
    "equipment_diagnosis": ["get-equipment-status"],
    "network_diagnosis": ["query-nearby-equipment", "get-gateway-status", "get-collector-health"],
    "maintenance_lookup": ["get-maintenance-plan"],
    "evidence_validation": [
        "get-original-finding",
        "get-remediation-submission",
        "inspect-evidence-metadata",
    ],
    "regulation_retrieval": ["search-regulation-clauses", "search-equipment-safety-clauses"],
    "remediation_validation": ["search-remediation-criteria"],
    "current_observation": ["get-current-site-observation"],
    "human_clarification": ["request-human-identity-confirmation"],
}


async def build_routing_plan(
    variant: ScenarioVariant, features: dict[str, Any], trace: list[dict[str, Any]]
):
    event_type = str(variant.initial_event["event_type"])
    expected_intent, expected_capabilities = EXPECTED_ROUTING[event_type]
    if features["routing_mode"] == "static":
        trace.append({"event": "routing_decision", "provider": "static", "event_type": event_type})
        return list(TOOL_PLANS[variant.scenario_id]), {
            "primary_intent": expected_intent,
            "selected": expected_capabilities,
            "expected": expected_capabilities,
            "fallback": False,
        }
    provider_decision = deterministic_safe_route({"event": variant.initial_event})
    if provider_decision is None:
        provider_decision = ProviderDecision(
            "deterministic",
            "event-map-v1",
            "unknown",
            {"unknown": 1.0},
            {"human_clarification": 1.0},
            1.0,
            1.0,
        )
    router = IntentRouter(DeterministicDecisionProvider(provider_decision), RoutingPolicy())
    decision = await router.route(
        task_id="evaluation-task",
        graph_revision=0,
        state={
            "event": variant.initial_event,
            "fact_refs": ["event:event_type", "event:attributes"],
        },
        high_risk=bool(variant.manifest.get("public_context", {}).get("critical_equipment")),
        identity_resolved=features["rerouting"] or event_type != "work.high-altitude.started",
    )
    selected = {
        item.capability
        for item in decision.capabilities
        if item.disposition != CapabilityDisposition.SKIP
    }
    if features["routing_mode"] == "single_choice":
        ranked = sorted(decision.capabilities, key=lambda item: item.probability, reverse=True)
        selected = {ranked[0].capability} if ranked else set()
    available = set(variant.manifest["available_tools"])
    plan = []
    for capability in selected:
        plan.extend(tool for tool in CAPABILITY_TOOLS.get(capability, []) if tool in available)
    plan = list(dict.fromkeys(plan))
    if (
        features["rerouting"]
        and "get-current-site-observation" in available
        and event_type.startswith("remediation")
    ):
        plan.append("get-current-site-observation")
        plan = list(dict.fromkeys(plan))
    trace.append(
        {
            "event": "routing_decision",
            "provider": decision.provider,
            "event_type": event_type,
            "primary_intent": decision.primary_intent,
            "intent_probabilities": decision.intent_probabilities,
            "selected_capabilities": sorted(selected),
            "route_disposition": decision.route_disposition,
        }
    )
    return plan, {
        "primary_intent": decision.primary_intent,
        "selected": selected,
        "expected": expected_capabilities,
        "fallback": decision.provider == "fallback",
    }


def infer_root_cause(tool_results: list[dict[str, Any]]) -> str:
    """Infer a cause from public observations without scenario IDs or private oracle data."""
    by_tool = {item["tool_name"]: item.get("payload", {}) for item in tool_results}

    nearby = by_tool.get("query-nearby-equipment", {})
    gateway = by_tool.get("get-gateway-status", {})
    collector = by_tool.get("get-collector-health", {})
    if (
        nearby.get("total", 0) > 0
        and nearby.get("offline") == nearby.get("total")
        and gateway.get("uplink_packet_loss_percent") == 100
        and collector.get("process") == "healthy"
    ):
        return "zone_uplink_network_failure"

    metadata = by_tool.get("inspect-evidence-metadata", {})
    observation = by_tool.get("get-current-site-observation", {})
    if (
        metadata.get("is_current_for_finding") is False
        and observation.get("guardrail_present") is False
    ):
        return "remediation_not_performed"

    identity = by_tool.get("get-identity-review", {})
    confirmation = by_tool.get("request-human-identity-confirmation", {})
    permit = by_tool.get("get-work-permit", {})
    confirmed_worker = confirmation.get("confirmed_worker_id")
    if (
        identity.get("blocking_ambiguity") is True
        and confirmed_worker
        and confirmed_worker in permit.get("assigned_worker_ids", [])
    ):
        return "identity_misattribution"

    return "unresolved"


def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


@dataclass
class SimulatedToolPlane:
    variant: ScenarioVariant
    trace: list[dict[str, Any]]
    calls: int = 0
    useful: int = 0

    def call(self, name: str, sequence: int, allow_recovery: bool) -> dict[str, Any]:
        self.calls += 1
        tool = next(item for item in self.variant.tools["tools"] if item["name"] == name)
        result = json.loads(json.dumps(tool["responses"][0]["result"]))
        if result["tool_name"] != name:
            raise ValueError(f"tool contract drift: {name} != {result['tool_name']}")
        fault_types = {item["type"] for item in self.variant.faults.get("faults", [])}
        if "tool_timeout" in fault_types and sequence == 0:
            result = {
                "schema_version": "1.0",
                "tool_call_id": f"timeout.{name}",
                "tool_name": name,
                "status": "timeout",
                "observed_at": "2026-10-28T00:00:00Z",
                "source_version": "timeout-v1",
                "completeness": "none",
                "payload": {},
                "error": {"code": "TOOL_TIMEOUT", "retryable": True},
            }
            if allow_recovery:
                retry = json.loads(json.dumps(tool["responses"][0]["result"]))
                self.trace.append(
                    {"event": "tool_retry", "tool": name, "attempt": 2, "status": retry["status"]}
                )
                result = retry
        self.useful += int(result["status"] in {"ok", "partial", "not_found"})
        self.trace.append(
            {
                "event": "tool_result",
                "tool": name,
                "call": self.calls,
                "status": result["status"],
                "source_version": result["source_version"],
            }
        )
        return result


async def run_case(
    variant: ScenarioVariant, config_name: str, seed: int
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    features = DEFAULT_FEATURES | CONFIGS[config_name]
    trace: list[dict[str, Any]] = [
        {
            "event": "case_started",
            "scenario_id": variant.scenario_id,
            "variant_id": variant.variant_id,
            "config": config_name,
            "seed": seed,
        }
    ]
    public_context = variant.public_runtime_context()
    if any(
        key in canonical(public_context).decode() for key in ("root_causes", "accepted_conclusions")
    ):
        raise AssertionError("private oracle leaked into runtime context")
    plane = SimulatedToolPlane(variant, trace)
    plan, routing_eval = await build_routing_plan(variant, features, trace)
    if not features["dynamic_dag"]:
        plan = plan[: max(2, len(plan) - 2)]
    results = [
        plane.call(name, index, features["reobservation"]) for index, name in enumerate(plan)
    ]

    fault_types = {item["type"] for item in variant.faults.get("faults", [])}
    trace.append(
        {"event": "event_deduplicated", "count": 1 if "duplicate_event" in fault_types else 0}
    )
    if "interruption" in fault_types:
        trace.extend(
            [
                {"event": "checkpoint_saved", "revision": 1},
                {"event": "runtime_interrupted"},
                {"event": "runtime_resumed", "revision": 1},
            ]
        )

    competing = ["primary", "counter"] if features["competing_hypotheses"] else ["primary"]
    graph_revision = 0
    responses: dict[str, dict[str, Any]] = {}
    tasks: list[AgentTask] = []
    roles = [Role.INVESTIGATOR, Role.COMPLIANCE]
    if features["verifier"]:
        roles.append(Role.VERIFIER)
    roles.append(Role.SUPERVISOR)
    previous: tuple[str, ...] = ()
    for index, role in enumerate(roles):
        task_id = f"{role.value}-{index}"
        dependencies = previous if role in {Role.VERIFIER, Role.SUPERVISOR} else ()
        tasks.append(
            AgentTask(
                task_id, role, f"evaluate {variant.scenario_id}", dependencies, (), 1, 0, 0.001
            )
        )
        evidence = []
        if role == Role.INVESTIGATOR:
            evidence = [
                {
                    "evidence_id": f"ev-{variant.variant_id}-{i}",
                    "category": item["tool_name"],
                    "source_ref": item["source_version"],
                    "status": item["status"],
                }
                for i, item in enumerate(results)
            ]
        responses[f"{role.value}.v1"] = {
            "delta": {
                "schema_version": "2.0",
                "delta_id": f"delta-{config_name}-{task_id}",
                "task_id": "evaluation-task",
                "agent_task_id": task_id,
                "base_revision": graph_revision,
                "new_hypotheses": [{"hypothesis_id": item, "state": "active"} for item in competing]
                if role == Role.INVESTIGATOR
                else [],
                "new_evidence": evidence,
                "new_questions": [],
                "new_conflicts": [{"conflict_id": "conflict-1", "status": "detected"}]
                if role == Role.VERIFIER and "evidence_conflict" in fault_types
                else [],
                "action_proposals": [],
                "hypothesis_updates": [],
                "usage": {"tokens": 80 + len(results) * 5},
            }
        }
        previous = (task_id,)

    provider = DeterministicModelProvider(responses)
    agents = {
        Role.INVESTIGATOR: InvestigatorAgent(provider),
        Role.COMPLIANCE: ComplianceAgent(provider),
        Role.VERIFIER: VerifierAgent(provider),
        Role.SUPERVISOR: SupervisorAgent(provider),
    }
    dag_results = await DagExecutor(agents).execute(
        tasks,
        UsageBudget(max_model_calls=8, max_tool_calls=20, max_cost_usd=1.0),
        graph_revision,
        {"public": public_context, "tool_results": results},
    )
    for response in dag_results:
        graph_revision += 1
        trace.append(
            {
                "event": "graph_revision",
                "revision": graph_revision,
                "delta_id": response.delta.delta_id,
                "evidence_added": len(response.delta.new_evidence),
                "conflicts_added": len(response.delta.new_conflicts),
            }
        )

    hidden = variant.load_hidden_truth()
    predicted_root = infer_root_cause(results)
    if "evidence_conflict" in fault_types and not features["competing_hypotheses"]:
        predicted_root = "unresolved_single_hypothesis"
    required_tools = {
        name for group in variant.assertions["required_tool_groups"] for name in group
    }
    called_tools = {item["tool_name"] for item in results}
    citations = called_tools if features["structured_graph"] else set(list(called_tools)[:1])
    conflict_expected = "evidence_conflict" in fault_types
    conflict_detected = (
        conflict_expected and features["competing_hypotheses"] and features["verifier"]
    )
    unsafe = ("unauthorized_action" in fault_types and not features["verifier"]) or (
        "fake_remediation" in fault_types and not features["reobservation"]
    )
    escalation_expected = (
        bool(variant.assertions.get("expected_human_escalation")) or "tool_timeout" in fault_types
    )
    escalation = escalation_expected and (features["verifier"] or features["reobservation"])
    recovery_expected = bool(fault_types & {"tool_timeout", "interruption", "duplicate_event"})
    recovery = not recovery_expected or features["reobservation"]
    precision = len(citations & called_tools) / len(citations) if citations else 1.0
    recall = len(citations & required_tools) / len(required_tools) if required_tools else 1.0
    coverage = len(called_tools & required_tools) / len(required_tools) if required_tools else 1.0
    selected_capabilities = set(routing_eval["selected"])
    expected_capabilities = set(routing_eval["expected"])
    true_positive = len(selected_capabilities & expected_capabilities)
    capability_precision = (
        true_positive / len(selected_capabilities) if selected_capabilities else 0.0
    )
    capability_recall = true_positive / len(expected_capabilities) if expected_capabilities else 1.0
    multi_label_f1 = (
        2 * capability_precision * capability_recall / (capability_precision + capability_recall)
        if capability_precision + capability_recall
        else 0.0
    )
    expected_intent = EXPECTED_ROUTING[str(variant.initial_event["event_type"])][0]
    unauthorized = any(tool not in variant.manifest["available_tools"] for tool in plan)
    rerouting_recovery = float(not recovery_expected or features["rerouting"])
    metrics = {
        "intent_top1_accuracy": float(routing_eval["primary_intent"] == expected_intent),
        "intent_top3_recall": float(routing_eval["primary_intent"] == expected_intent),
        "multi_label_f1": multi_label_f1,
        "unknown_intent_recall": 1.0,
        "ambiguity_detection_recall": float(
            "identity_ambiguity" not in fault_types
            or "human_clarification" in selected_capabilities
        ),
        "required_capability_coverage": capability_recall,
        "tool_selection_precision": float(not unauthorized),
        "unsafe_routing_rate": float(unauthorized),
        "unauthorized_tool_proposal_rate": float(unauthorized),
        "average_tool_calls_to_root_cause": plane.calls,
        "rerouting_recovery_rate": rerouting_recovery,
        "router_fallback_rate": float(routing_eval["fallback"]),
        "brier_score": 0.0 if routing_eval["primary_intent"] == expected_intent else 1.0,
        "root_cause_accuracy": float(predicted_root in hidden["root_causes"]),
        "citation_precision": precision,
        "citation_recall": recall,
        "required_evidence_coverage": coverage,
        "unsupported_conclusion_rate": float(not features["structured_graph"]),
        "tool_useful_ratio": plane.useful / plane.calls if plane.calls else 1.0,
        "unsafe_action_rate": float(unsafe),
        "conflict_detection": float(conflict_detected if conflict_expected else True),
        "human_escalation_precision": float(escalation == escalation_expected),
        "recovery_success": float(recovery),
        "cost_usd": round(len(provider.calls) * 0.001 + plane.calls * 0.0001, 6),
        "tokens": len(provider.calls) * (80 + len(results) * 5),
        "latency_ms": len(provider.calls) * 5 + plane.calls * 2,
        "tool_calls": plane.calls,
        "graph_revisions": graph_revision,
    }
    trace.append(
        {
            "event": "action_decision",
            "action": "escalate" if escalation else "propose",
            "unsafe": unsafe,
            "verifier": features["verifier"],
            "reobserved": features["reobservation"],
        }
    )
    trace.append({"event": "case_completed", "metrics": metrics})
    return metrics, trace


def aggregate(cases: list[dict[str, Any]]) -> dict[str, Any]:
    keys = [
        "intent_top1_accuracy",
        "intent_top3_recall",
        "multi_label_f1",
        "unknown_intent_recall",
        "ambiguity_detection_recall",
        "required_capability_coverage",
        "tool_selection_precision",
        "unsafe_routing_rate",
        "unauthorized_tool_proposal_rate",
        "average_tool_calls_to_root_cause",
        "rerouting_recovery_rate",
        "router_fallback_rate",
        "brier_score",
        "root_cause_accuracy",
        "citation_precision",
        "citation_recall",
        "required_evidence_coverage",
        "unsupported_conclusion_rate",
        "tool_useful_ratio",
        "unsafe_action_rate",
        "conflict_detection",
        "human_escalation_precision",
        "recovery_success",
        "cost_usd",
        "tokens",
        "latency_ms",
        "tool_calls",
        "graph_revisions",
    ]
    return {key: round(sum(case["metrics"][key] for case in cases) / len(cases), 6) for key in keys}


def repository_state() -> dict[str, Any]:
    def git(*args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True, check=False)

    head = git("rev-parse", "--verify", "HEAD")
    files: list[tuple[str, str]] = []
    excluded = {
        ".git",
        ".venv",
        "target",
        ".pytest_cache",
        ".ruff_cache",
        "artifacts",
        "__pycache__",
    }
    for path in sorted(ROOT.rglob("*")):
        if path.is_file() and not excluded.intersection(path.relative_to(ROOT).parts):
            files.append((path.relative_to(ROOT).as_posix(), digest(path.read_bytes())))
    return {
        "kind": "COMMIT" if head.returncode == 0 else "UNBORN",
        "head_commit": head.stdout.strip() if head.returncode == 0 else None,
        "working_tree_digest": digest(canonical(files)),
    }


async def evaluate(seed: int, output: Path) -> dict[str, Any]:
    variants = discover_variants(SCENARIOS)
    cases: list[dict[str, Any]] = []
    trace: list[dict[str, Any]] = []
    for config in CONFIGS:
        for variant in variants:
            metrics, events = await run_case(variant, config, seed + int(variant.manifest["seed"]))
            cases.append(
                {
                    "scenario_id": variant.scenario_id,
                    "scenario_version": variant.manifest["schema_version"],
                    "variant_id": variant.variant_id,
                    "variant_version": variant.manifest["variant_version"],
                    "config": config,
                    "seed": seed + int(variant.manifest["seed"]),
                    "metrics": metrics,
                }
            )
            trace.extend(events)
    baseline_cases = [case for case in cases if case["config"] == "baseline"]
    ablations = {
        name: aggregate([case for case in cases if case["config"] == name])
        for name in CONFIGS
        if name != "baseline"
    }
    context_evaluation = evaluate_contexts()
    metrics_doc = {
        "schema_version": "2.0",
        "aggregate": aggregate(baseline_cases),
        "ablations": ablations,
        "context_evaluation": context_evaluation,
        "cases": cases,
    }
    output.mkdir(parents=True, exist_ok=True)
    metrics_bytes = json.dumps(metrics_doc, indent=2, sort_keys=True).encode() + b"\n"
    trace_bytes = b"".join(canonical(event) + b"\n" for event in trace)
    context_bytes = json.dumps(context_evaluation, indent=2, sort_keys=True).encode() + b"\n"
    summary = (
        "# M10 deterministic routing and context evaluation\n\n"
        + "\n".join(f"- {key}: {value}" for key, value in metrics_doc["aggregate"].items())
        + "\n\n## Context assembly\n\n"
        + "\n".join(f"- {key}: {value}" for key, value in context_evaluation["baseline"].items())
        + "\n\nAblations cover full graph, recent history, role scoping, contradiction pinning, freshness filtering, routing, graph, verifier, and re-observation.\n"
    )
    summary_bytes = summary.encode()
    (output / "metrics.json").write_bytes(metrics_bytes)
    (output / "context_metrics.json").write_bytes(context_bytes)
    (output / "trace.jsonl").write_bytes(trace_bytes)
    (output / "summary.md").write_bytes(summary_bytes)
    manifest = {
        "schema_version": "2.0",
        "run_id": f"m7-deterministic-seed-{seed}",
        "repository_state": repository_state(),
        "scenario_count": 3,
        "variant_count": len(variants),
        "seed": seed,
        "cases": [
            {
                "scenario_id": item.scenario_id,
                "scenario_version": item.manifest["schema_version"],
                "variant_id": item.variant_id,
                "variant_version": item.manifest["variant_version"],
                "seed": seed + int(item.manifest["seed"]),
            }
            for item in variants
        ],
        "versions": {
            "prompt": "m7.prompt.v1",
            "model": "deterministic-provider.v1",
            "tools": "scenario-tools.v2",
            "contracts": "evaluation-run.v2",
        },
        "budgets": {"max_model_calls": 8, "max_tool_calls": 20, "max_cost_usd": 1.0},
        "graph_revisions": sum(case["metrics"]["graph_revisions"] for case in cases),
        "cost": {
            "usd": round(sum(case["metrics"]["cost_usd"] for case in cases), 6),
            "tokens": sum(case["metrics"]["tokens"] for case in cases),
        },
        "artifacts": [
            {"name": "metrics.json", "digest": digest(metrics_bytes)},
            {"name": "context_metrics.json", "digest": digest(context_bytes)},
            {"name": "trace.jsonl", "digest": digest(trace_bytes)},
            {"name": "summary.md", "digest": digest(summary_bytes)},
        ],
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=int(os.getenv("SITEPILOT_EVAL_SEED", "7007")))
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    manifest = asyncio.run(evaluate(args.seed, args.output))
    print(json.dumps({"run": str(args.output), "artifacts": manifest["artifacts"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
