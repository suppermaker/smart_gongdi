from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import jsonschema

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from evaluate import evaluate, infer_root_cause
from loader import discover_variants, load_document

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = ROOT / "scenarios"


def test_loader_supports_true_yaml_and_keeps_private_oracle_out_of_runtime() -> None:
    variants = discover_variants(SCENARIOS)
    assert len(variants) == 12
    assert len({item.scenario_id for item in variants}) == 3
    assert all({"normal", "adversarial"} & set(item.manifest["tags"]) for item in variants)
    for variant in variants:
        public = json.dumps(variant.public_runtime_context())
        assert "root_causes" not in public
        assert "accepted_conclusions" not in public
        hidden = variant.load_hidden_truth()
        assert hidden["root_causes"]
        assert variant.assertions["required_tool_groups"]
        assert "faults" in variant.faults


def test_true_yaml_parser_is_not_json_only(tmp_path: Path) -> None:
    path = tmp_path / "real.yaml"
    path.write_text("name: real-yaml\nitems:\n  - one\n  - two\n", encoding="utf-8")
    assert load_document(path) == {"name": "real-yaml", "items": ["one", "two"]}


def test_evaluation_is_reproducible_and_records_unborn_repository(tmp_path: Path) -> None:
    first = tmp_path / "first"
    second = tmp_path / "second"
    manifest1 = asyncio.run(evaluate(7007, first))
    manifest2 = asyncio.run(evaluate(7007, second))
    assert manifest1["artifacts"] == manifest2["artifacts"]
    assert manifest1["repository_state"] == manifest2["repository_state"]
    assert manifest1["repository_state"]["kind"] in {"UNBORN", "COMMIT"}
    if manifest1["repository_state"]["kind"] == "UNBORN":
        assert manifest1["repository_state"]["head_commit"] is None
    schema = json.loads(
        (ROOT.parents[1] / "contracts/evaluation-run/v2.schema.json").read_text(encoding="utf-8")
    )
    jsonschema.validate(manifest1, schema)
    assert len(manifest1["cases"]) == 12
    metrics = json.loads((first / "metrics.json").read_text(encoding="utf-8"))
    assert metrics["aggregate"]["root_cause_accuracy"] == 1.0
    assert set(metrics["ablations"]) == {
        "chat_history",
        "structured_graph",
        "single_hypothesis",
        "competing",
        "fixed_pipeline",
        "dynamic_dag",
        "without_verifier",
        "verifier",
        "without_reobservation",
        "reobservation",
        "static_routing",
        "single_choice_routing",
        "multi_label_routing",
        "without_rerouting",
        "evidence_aware_routing",
    }
    assert metrics["aggregate"]["intent_top1_accuracy"] == 1.0
    assert metrics["aggregate"]["required_capability_coverage"] >= 0.8
    assert metrics["aggregate"]["unsafe_routing_rate"] == 0.0
    assert metrics["aggregate"]["unauthorized_tool_proposal_rate"] == 0.0
    context_metrics = metrics["context_evaluation"]["baseline"]
    assert context_metrics["required_context_recall"] == 1.0
    assert context_metrics["contradicting_evidence_recall"] == 1.0
    assert context_metrics["expired_evidence_leakage_rate"] == 0.0
    assert context_metrics["cross_tenant_leakage_rate"] == 0.0
    assert (first / "context_metrics.json").is_file()
    assert len((first / "trace.jsonl").read_text(encoding="utf-8").splitlines()) > 100


def test_root_cause_is_inferred_from_public_tool_observations() -> None:
    assert (
        infer_root_cause(
            [
                {"tool_name": "query-nearby-equipment", "payload": {"total": 5, "offline": 5}},
                {"tool_name": "get-gateway-status", "payload": {"uplink_packet_loss_percent": 100}},
                {"tool_name": "get-collector-health", "payload": {"process": "healthy"}},
            ]
        )
        == "zone_uplink_network_failure"
    )
    assert (
        infer_root_cause(
            [
                {
                    "tool_name": "inspect-evidence-metadata",
                    "payload": {"is_current_for_finding": False},
                },
                {
                    "tool_name": "get-current-site-observation",
                    "payload": {"guardrail_present": False},
                },
            ]
        )
        == "remediation_not_performed"
    )
    assert (
        infer_root_cause(
            [
                {"tool_name": "get-identity-review", "payload": {"blocking_ambiguity": True}},
                {
                    "tool_name": "request-human-identity-confirmation",
                    "payload": {"confirmed_worker_id": "worker.2048"},
                },
                {
                    "tool_name": "get-work-permit",
                    "payload": {"assigned_worker_ids": ["worker.2048"]},
                },
            ]
        )
        == "identity_misattribution"
    )
    assert "ROOT_CAUSE_POLICY" not in (ROOT / "evaluate.py").read_text(encoding="utf-8")
