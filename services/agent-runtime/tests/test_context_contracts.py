import copy
import json
from dataclasses import replace
from pathlib import Path

import pytest

from sitepilot_agent_runtime.context_contracts import (
    AgentContextInput,
    AgentContextOutput,
    ContextContractError,
    ContextDeltaAssembler,
)

ROOT = Path(__file__).resolve().parents[3]


def response():
    pack = json.loads(
        (ROOT / "contracts/context-pack/examples/valid.json").read_text(encoding="utf-8")
    )
    return {
        "contextId": pack["context_id"],
        "contextDigest": pack["manifest"]["context_digest"],
        "graphRevision": pack["graph_revision"],
        "estimatedTokens": pack["manifest"]["estimated_tokens"],
        "contextPack": pack,
        "applied": True,
    }


def request(policy="context-policy.v1", tools=None):
    return AgentContextInput(
        "task-001",
        "route-4-network_diagnosis",
        "investigator",
        4,
        0,
        policy,
        "investigator.v2",
        tools or ("query-nearby-equipment", "get-gateway-status", "get-collector-health"),
    )


def v2_response():
    pack = json.loads(
        (ROOT / "contracts/context-pack/examples/v2-valid.json").read_text(encoding="utf-8")
    )
    return {
        "contextId": pack["context_id"],
        "contextDigest": pack["manifest"]["context_digest"],
        "graphRevision": pack["graph_revision"],
        "estimatedTokens": pack["manifest"]["estimated_tokens"],
        "contextPack": pack,
        "applied": True,
    }


def test_context_contract_validates_scope_digest_policy_and_tools():
    result = AgentContextOutput.validate(response(), request())
    assert result.context_id == "ctx-task-001-r4-network"
    assert result.context_pack["role"] == "investigator"

    escalated = response()
    escalated["contextPack"]["allowed_tools"].append("merge_graph_delta")
    with pytest.raises(ContextContractError, match="CONTEXT_TOOL_ESCALATION"):
        AgentContextOutput.validate(escalated, request())

    wrong_revision = response()
    wrong_revision["contextPack"]["graph_revision"] = 5
    with pytest.raises(ContextContractError, match="CONTEXT_SCOPE_MISMATCH"):
        AgentContextOutput.validate(wrong_revision, request())


def test_v2_full_and_delta_materialize_with_verified_parent_digest():
    full = AgentContextOutput.validate(
        v2_response(),
        request("context-policy.v2", ("get-gateway-status", "get_evidence_detail")),
    )
    assembler = ContextDeltaAssembler()
    materialized = assembler.materialize(full)
    assert materialized["evidence_cards"][0]["evidence_id"] == "ev-network"

    value = v2_response()
    value["contextId"] = "ctx-task-001-r5-network-v2"
    value["contextDigest"] = "sha256:" + "c" * 64
    value["graphRevision"] = 5
    pack = value["contextPack"]
    pack["context_id"] = value["contextId"]
    pack["graph_revision"] = 5
    pack["context_mode"] = "DELTA"
    pack["parent_context"] = {
        "context_id": full.context_id,
        "context_digest": full.context_digest,
        "graph_revision": 4,
    }
    pack["evidence_cards"] = [{"evidence_id": "ev-counter", "claim": "Controller healthy"}]
    pack["evidence_handles"] = [{
        "evidence_id": "ev-counter",
        "detail_tool": "get_evidence_detail",
        "source_digest": "sha256:" + "d" * 64,
    }]
    pack["delta"] = {
        "added_node_ids": ["ev-counter"],
        "updated_node_ids": [],
        "removed_node_ids": [],
        "expired_node_ids": [],
    }
    pack["manifest"]["context_digest"] = value["contextDigest"]
    delta_request = replace(
        request("context-policy.v2", ("get-gateway-status", "get_evidence_detail")),
        graph_revision=5,
    )
    delta = AgentContextOutput.validate(value, delta_request)
    merged = assembler.materialize(delta)
    assert {item["evidence_id"] for item in merged["evidence_cards"]} == {
        "ev-network",
        "ev-counter",
    }

def test_v2_delta_fails_closed_without_parent_or_with_wrong_digest():
    full = AgentContextOutput.validate(
        v2_response(),
        request("context-policy.v2", ("get-gateway-status", "get_evidence_detail")),
    )
    delta = copy.deepcopy(full.context_pack)
    delta["context_id"] = "ctx-delta"
    delta["graph_revision"] = 5
    delta["context_mode"] = "DELTA"
    delta["parent_context"] = {
        "context_id": full.context_id,
        "context_digest": "sha256:" + "e" * 64,
        "graph_revision": 4,
    }
    output = AgentContextOutput("ctx-delta", "sha256:" + "f" * 64, 5, 20, delta, True)
    with pytest.raises(ContextContractError, match="CONTEXT_PARENT_MISSING"):
        ContextDeltaAssembler().materialize(output)

    assembler = ContextDeltaAssembler()
    assembler.materialize(full)
    with pytest.raises(ContextContractError, match="CONTEXT_PARENT_DIGEST_MISMATCH"):
        assembler.materialize(output)
