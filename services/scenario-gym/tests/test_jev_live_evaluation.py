from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sitepilot_agent_runtime.routing_contracts import CAPABILITIES

from jev_live_eval import load_cases, score_selection

ROOT = Path(__file__).resolve().parents[1]


def test_realistic_jev_cases_are_complex_versioned_and_do_not_leak_oracles():
    cases = load_cases(ROOT / "jev_complex_cases.yaml")
    assert len(cases) == 18
    assert len({case["case_id"] for case in cases}) == 18
    assert {case["expected_primary_intent"] for case in cases} >= {
        "equipment_local_failure",
        "zone_connectivity_failure",
        "identity_or_qualification_risk",
        "remediation_not_performed",
        "compliance_gap",
        "unknown",
    }
    for case in cases:
        assert set(case["required_capabilities"]) <= CAPABILITIES
        assert len(case["required_capabilities"]) >= 2
        assert case["state"]["fact_refs"]
        state_text = str(case["state"])
        assert "expected_primary_intent" not in state_text
        assert "required_capabilities" not in state_text
        assert "expected_human" not in state_text


def test_multi_label_scoring_rewards_coverage_without_ignoring_precision():
    expected = {"equipment_observation", "network_diagnosis", "maintenance_lookup"}
    single = score_selection({"network_diagnosis"}, expected)
    multi = score_selection(
        {"equipment_observation", "network_diagnosis", "maintenance_lookup"}, expected
    )
    noisy = score_selection(
        {
            "equipment_observation",
            "network_diagnosis",
            "maintenance_lookup",
            "human_clarification",
        },
        expected,
    )
    assert single["recall"] == 1 / 3
    assert multi["f1"] == 1.0
    assert noisy["recall"] == 1.0
    assert noisy["precision"] < 1.0
