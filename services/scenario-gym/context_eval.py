"""Deterministic context compiler v2 evaluation."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml

HERE = Path(__file__).resolve().parent
CONFIGS = {
    "role_context": {},
    "full_graph_context": {"mode": "full"},
    "recent_history_context": {"mode": "recent"},
    "without_contradiction_pinning": {"pin_contradictions": False},
    "without_freshness_filter": {"freshness_filter": False},
    "without_role_scoping": {"role_scoping": False},
    "without_stage_budget": {"stage_budget": False},
    "without_delta": {"delta_context": False},
    "without_progressive_loading": {"progressive_loading": False},
}
DEFAULTS = {
    "mode": "role",
    "pin_contradictions": True,
    "freshness_filter": True,
    "role_scoping": True,
    "stage_budget": True,
    "delta_context": True,
    "progressive_loading": True,
}


def load_cases(path: Path | None = None) -> list[dict[str, Any]]:
    source = path or HERE / "context_cases.yaml"
    return yaml.safe_load(source.read_text(encoding="utf-8"))["cases"]


def select_context(case: dict[str, Any], config_name: str) -> list[dict[str, Any]]:
    features = DEFAULTS | CONFIGS[config_name]
    nodes = [dict(node) for node in case["nodes"]]
    if features["mode"] == "full":
        return [node for node in nodes if node.get("tenant", "tenant-a") == "tenant-a"]
    candidates = []
    for node in nodes:
        if node.get("tenant", "tenant-a") != "tenant-a":
            continue
        if node.get("contradiction", False) and not features["pin_contradictions"]:
            continue
        if features["mode"] == "recent" and not node.get("recent", True):
            continue
        if features["role_scoping"] and node.get("role", "common") not in {
            "common",
            case["role"],
        }:
            continue
        if features["freshness_filter"] and node.get("expired", False):
            continue
        candidates.append(node)

    def mandatory(node: dict[str, Any]) -> bool:
        if node.get("critical_conflict", False):
            return True
        if node.get("required", False) and not node.get("contradiction", False):
            return True
        return bool(node.get("contradiction", False) and features["pin_contradictions"])

    def priority(node: dict[str, Any]) -> tuple[int, float]:
        if not features["stage_budget"]:
            return (5, -float(node.get("score", 0.0)))
        if node.get("critical_conflict", False):
            return (0, -float(node.get("score", 0.0)))
        if node.get("contradiction", False):
            return (1, -float(node.get("score", 0.0)))
        if case.get("stage") == "closure" and node.get("current_observation", False):
            return (2, -float(node.get("score", 0.0)))
        if node.get("required", False):
            return (3, -float(node.get("score", 0.0)))
        return (5, -float(node.get("score", 0.0)))

    selected = sorted((node for node in candidates if mandatory(node)), key=priority)
    used = sum(int(node.get("tokens", 0)) for node in selected)
    optional = sorted((node for node in candidates if node not in selected), key=priority)
    for node in optional:
        tokens = int(node.get("tokens", 0))
        if used + tokens <= int(case["budget_tokens"]):
            selected.append(node)
            used += tokens
    return selected


def case_metrics(case: dict[str, Any], config_name: str) -> dict[str, float]:
    features = DEFAULTS | CONFIGS[config_name]
    selected = select_context(case, config_name)
    selected_ids = {node["id"] for node in selected}
    required = {node["id"] for node in case["nodes"] if node.get("required", False)}
    critical = {node["id"] for node in case["nodes"] if node.get("critical_conflict", False)}
    contradictions = {node["id"] for node in case["nodes"] if node.get("contradiction", False)}
    expired = {node["id"] for node in case["nodes"] if node.get("expired", False)}
    irrelevant = {node["id"] for node in case["nodes"] if node.get("irrelevant", False)}
    foreign = {node["id"] for node in case["nodes"] if node.get("tenant") == "tenant-b"}
    current_observation = any(node.get("current_observation", False) for node in selected)
    open_critical = any(node.get("critical_conflict", False) for node in selected)
    false_closure = bool(case.get("closure") and (not current_observation or critical and not open_critical))
    replay = select_context(case, config_name) == select_context(case, config_name)

    full_tokens = sum(int(node.get("tokens", 0)) for node in selected)
    if not features["progressive_loading"]:
        full_tokens += sum(int(node.get("detail_tokens", 40)) for node in selected)
    changed_ids = set(case.get("changed_node_ids", []))
    if not changed_ids and selected:
        changed_ids = {selected[0]["id"]}
    delta_tokens = (
        30 + sum(int(node.get("tokens", 0)) for node in selected if node["id"] in changed_ids)
        if features["delta_context"]
        else full_tokens
    )
    delta_tokens = min(delta_tokens, full_tokens)
    detail_calls = sum(1 for node in selected if node.get("detail_required", False))

    return {
        "required_context_recall": ratio(len(selected_ids & required), len(required)),
        "critical_conflict_recall": ratio(len(selected_ids & critical), len(critical)),
        "contradicting_evidence_recall": ratio(len(selected_ids & contradictions), len(contradictions)),
        "expired_evidence_leakage_rate": leakage(len(selected_ids & expired), len(expired)),
        "irrelevant_context_rate": ratio(len(selected_ids & irrelevant), len(selected_ids)),
        "average_input_tokens": float(full_tokens),
        "average_delta_tokens": float(delta_tokens),
        "delta_to_full_ratio": ratio(delta_tokens, full_tokens),
        "on_demand_detail_calls": float(detail_calls),
        "context_replay_consistency": float(replay),
        "cross_tenant_leakage_rate": ratio(len(selected_ids & foreign), len(selected_ids)),
        "false_closure_rate": float(false_closure),
    }


def evaluate_contexts() -> dict[str, Any]:
    cases = load_cases()
    results = {config: [case_metrics(case, config) for case in cases] for config in CONFIGS}
    return {
        "case_count": len(cases),
        "baseline": aggregate(results["role_context"]),
        "ablations": {
            config: aggregate(metrics)
            for config, metrics in results.items()
            if config != "role_context"
        },
        "cases": [
            {"case_id": case["id"], "metrics": case_metrics(case, "role_context")}
            for case in cases
        ],
    }


def aggregate(values: list[dict[str, float]]) -> dict[str, float]:
    return {key: round(sum(value[key] for value in values) / len(values), 6) for key in values[0]}


def ratio(numerator: float, denominator: float) -> float:
    return numerator / denominator if denominator else 1.0


def leakage(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def main() -> int:
    print(json.dumps(evaluate_contexts(), indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
