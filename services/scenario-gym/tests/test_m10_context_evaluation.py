import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from context_eval import CONFIGS, evaluate_contexts, load_cases, select_context


def test_context_cases_cover_roles_conflicts_expiry_budget_and_tenant_isolation():
    cases = load_cases()
    assert len(cases) == 12
    assert {case["role"] for case in cases} >= {"investigator", "compliance", "verifier"}
    assert any(any(node.get("critical_conflict") for node in case["nodes"]) for case in cases)
    assert any(any(node.get("expired") for node in case["nodes"]) for case in cases)
    assert any(any(node.get("tenant") == "tenant-b" for node in case["nodes"]) for case in cases)
    assert any(case.get("changed_node_ids") for case in cases)


def test_role_context_is_deterministic_safe_and_preserves_counterevidence():
    result = evaluate_contexts()
    baseline = result["baseline"]
    assert baseline["required_context_recall"] == 1.0
    assert baseline["critical_conflict_recall"] == 1.0
    assert baseline["contradicting_evidence_recall"] == 1.0
    assert baseline["expired_evidence_leakage_rate"] == 0.0
    assert baseline["context_replay_consistency"] == 1.0
    assert baseline["cross_tenant_leakage_rate"] == 0.0
    assert baseline["false_closure_rate"] == 0.0
    assert baseline["delta_to_full_ratio"] <= 0.6
    assert baseline["irrelevant_context_rate"] <= 0.027778
    assert set(CONFIGS) == {
        "role_context",
        "full_graph_context",
        "recent_history_context",
        "without_contradiction_pinning",
        "without_freshness_filter",
        "without_role_scoping",
        "without_stage_budget",
        "without_delta",
        "without_progressive_loading",
    }
    assert result["ablations"]["without_freshness_filter"]["expired_evidence_leakage_rate"] > 0
    assert result["ablations"]["without_contradiction_pinning"]["contradicting_evidence_recall"] < 1
    assert result["ablations"]["without_delta"]["delta_to_full_ratio"] == 1.0
    assert (
        result["ablations"]["without_progressive_loading"]["average_input_tokens"]
        > baseline["average_input_tokens"]
    )


def test_same_context_case_selects_same_node_sequence():
    case = load_cases()[0]
    assert select_context(case, "role_context") == select_context(case, "role_context")
