"""Typed, revision-scoped model context contracts."""

from __future__ import annotations

import copy
import re
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class AgentContextInput:
    task_id: str
    agent_task_id: str
    role: str
    graph_revision: int
    task_version: int
    context_policy_version: str
    prompt_version: str
    allowed_tools: tuple[str, ...]


@dataclass(frozen=True)
class AgentContextOutput:
    context_id: str
    context_digest: str
    graph_revision: int
    estimated_tokens: int
    context_pack: dict[str, Any]
    applied: bool

    @classmethod
    def validate(cls, value: dict[str, Any], request: AgentContextInput) -> AgentContextOutput:
        required = {
            "contextId",
            "contextDigest",
            "graphRevision",
            "estimatedTokens",
            "contextPack",
        }
        missing = sorted(required - value.keys())
        if missing:
            raise ContextContractError("CONTEXT_RESPONSE_INVALID", f"missing {missing}")
        pack = value["contextPack"]
        if not isinstance(pack, dict):
            raise ContextContractError("CONTEXT_RESPONSE_INVALID", "contextPack must be object")
        version = pack.get("schema_version")
        if version not in {"1.0", "2.0"}:
            raise ContextContractError("CONTEXT_VERSION_UNSUPPORTED", "context version")
        expected_version = "2.0" if request.context_policy_version == "context-policy.v2" else "1.0"
        if version != expected_version:
            raise ContextContractError("CONTEXT_VERSION_UNSUPPORTED", "policy/version mismatch")
        checks = {
            "task_id": request.task_id,
            "agent_task_id": request.agent_task_id,
            "role": request.role,
            "graph_revision": request.graph_revision,
        }
        for field, expected in checks.items():
            if pack.get(field) != expected:
                raise ContextContractError("CONTEXT_SCOPE_MISMATCH", field)
        returned_tools = set(pack.get("allowed_tools", ()))
        if returned_tools - set(request.allowed_tools):
            raise ContextContractError("CONTEXT_TOOL_ESCALATION", ",".join(returned_tools))
        digest = str(value["contextDigest"])
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", digest):
            raise ContextContractError("CONTEXT_DIGEST_INVALID", digest)
        manifest = pack.get("manifest", {})
        if manifest.get("context_digest") != digest:
            raise ContextContractError("CONTEXT_DIGEST_MISMATCH", "manifest")
        if manifest.get("context_policy_version") != request.context_policy_version:
            raise ContextContractError("CONTEXT_POLICY_MISMATCH", "policy")
        if manifest.get("prompt_version") != request.prompt_version:
            raise ContextContractError("CONTEXT_PROMPT_MISMATCH", "prompt")
        if version == "2.0":
            _validate_v2(pack)
        return cls(
            context_id=str(value["contextId"]),
            context_digest=digest,
            graph_revision=int(value["graphRevision"]),
            estimated_tokens=int(value["estimatedTokens"]),
            context_pack=pack,
            applied=bool(value.get("applied", False)),
        )


def _validate_v2(pack: dict[str, Any]) -> None:
    phase = pack.get("context_phase")
    if phase not in {"EXPLORATION", "CONFLICT_RESOLUTION", "CONCLUSION", "CLOSURE"}:
        raise ContextContractError("CONTEXT_PHASE_INVALID", str(phase))
    mode = pack.get("context_mode")
    parent = pack.get("parent_context")
    if mode == "FULL" and parent is not None:
        raise ContextContractError("CONTEXT_PARENT_INVALID", "full context has parent")
    if mode == "DELTA":
        if not isinstance(parent, dict):
            raise ContextContractError("CONTEXT_PARENT_MISSING", "delta parent")
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", str(parent.get("context_digest", ""))):
            raise ContextContractError("CONTEXT_PARENT_INVALID", "parent digest")
        if int(parent.get("graph_revision", -1)) >= int(pack["graph_revision"]):
            raise ContextContractError("CONTEXT_PARENT_INVALID", "parent revision")
    elif mode != "FULL":
        raise ContextContractError("CONTEXT_MODE_INVALID", str(mode))
    if not isinstance(pack.get("reasoning_frontier"), dict):
        raise ContextContractError("CONTEXT_FRONTIER_INVALID", "reasoning_frontier")
    for handle in pack.get("evidence_handles", []):
        if handle.get("detail_tool") != "get_evidence_detail":
            raise ContextContractError("CONTEXT_EVIDENCE_HANDLE_INVALID", str(handle))


class ContextDeltaAssembler:
    """Deterministically materialize validated v2 deltas from an in-memory parent cache."""

    def __init__(self, max_chain_depth: int = 3) -> None:
        self.max_chain_depth = max_chain_depth
        self._by_id: dict[str, tuple[str, dict[str, Any], int]] = {}

    def materialize(self, loaded: AgentContextOutput) -> dict[str, Any]:
        pack = loaded.context_pack
        if pack.get("schema_version") != "2.0" or pack.get("context_mode") == "FULL":
            materialized = copy.deepcopy(pack)
            self._by_id[loaded.context_id] = (loaded.context_digest, materialized, 0)
            return materialized

        parent_ref = pack["parent_context"]
        parent_id = str(parent_ref["context_id"])
        cached = self._by_id.get(parent_id)
        if cached is None:
            raise ContextContractError("CONTEXT_PARENT_MISSING", parent_id)
        parent_digest, parent, depth = cached
        if parent_digest != parent_ref["context_digest"]:
            raise ContextContractError("CONTEXT_PARENT_DIGEST_MISMATCH", parent_id)
        if depth >= self.max_chain_depth:
            raise ContextContractError("CONTEXT_DELTA_CHAIN_EXCEEDED", parent_id)

        result = copy.deepcopy(parent)
        for field in (
            "context_id", "graph_revision", "context_phase", "context_mode",
            "parent_context", "event_summary", "risk_summary", "active_hypotheses",
            "open_conflicts", "unresolved_questions", "action_context",
            "reasoning_frontier", "allowed_capabilities", "allowed_tools", "budget", "manifest",
        ):
            if field in pack:
                result[field] = copy.deepcopy(pack[field])
        cards = {item["evidence_id"]: item for item in result.get("evidence_cards", [])}
        handles = {item["evidence_id"]: item for item in result.get("evidence_handles", [])}
        delta = pack.get("delta", {})
        for evidence_id in (*delta.get("removed_node_ids", []), *delta.get("expired_node_ids", [])):
            cards.pop(evidence_id, None)
            handles.pop(evidence_id, None)
        for item in pack.get("evidence_cards", []):
            cards[item["evidence_id"]] = copy.deepcopy(item)
        for item in pack.get("evidence_handles", []):
            handles[item["evidence_id"]] = copy.deepcopy(item)
        result["evidence_cards"] = [cards[key] for key in sorted(cards)]
        result["evidence_handles"] = [handles[key] for key in sorted(handles)]
        result["context_mode"] = "FULL"
        result["materialized_from_delta"] = True
        self._by_id[loaded.context_id] = (loaded.context_digest, result, depth + 1)
        return result


class ContextContractError(RuntimeError):
    def __init__(self, code: str, detail: str) -> None:
        self.code = code
        super().__init__(f"{code}: {detail}")
