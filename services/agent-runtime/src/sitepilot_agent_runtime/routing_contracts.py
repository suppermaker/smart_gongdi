"""Typed contracts for evidence-aware investigation routing."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import StrEnum
from typing import Any


class CapabilityDisposition(StrEnum):
    REQUIRED = "REQUIRED"
    OPTIONAL_IF_BUDGET = "OPTIONAL_IF_BUDGET"
    SKIP = "SKIP"


class RouteDisposition(StrEnum):
    AUTO_ROUTE = "AUTO_ROUTE"
    COLLECT_EVIDENCE = "COLLECT_EVIDENCE"
    HUMAN_CLARIFICATION = "HUMAN_CLARIFICATION"
    HUMAN_ESCALATION = "HUMAN_ESCALATION"


CAPABILITIES = frozenset(
    {
        "identity_resolution",
        "certificate_verification",
        "permit_validation",
        "equipment_observation",
        "equipment_diagnosis",
        "network_diagnosis",
        "maintenance_lookup",
        "evidence_validation",
        "regulation_retrieval",
        "remediation_validation",
        "current_observation",
        "plan_verification",
        "conclusion_verification",
        "closure_verification",
        "human_clarification",
    }
)


@dataclass(frozen=True)
class CapabilityDecision:
    capability: str
    probability: float
    disposition: CapabilityDisposition
    supporting_fact_refs: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.capability not in CAPABILITIES:
            raise ValueError(f"unknown capability: {self.capability}")
        if not 0.0 <= self.probability <= 1.0:
            raise ValueError("capability probability must be between 0 and 1")


@dataclass(frozen=True)
class InvestigationRoutingDecision:
    schema_version: str
    decision_id: str
    task_id: str
    graph_revision: int
    policy_version: str
    provider: str
    model_version: str
    input_digest: str
    primary_intent: str
    primary_confidence: float
    intent_probabilities: dict[str, float]
    capabilities: tuple[CapabilityDecision, ...]
    human_probability: float
    unknown_probability: float
    urgency_score: float
    route_disposition: RouteDisposition
    reason_codes: tuple[str, ...]
    created_at: str

    def __post_init__(self) -> None:
        if self.schema_version != "1.0":
            raise ValueError("unsupported routing decision version")
        if self.graph_revision < 0:
            raise ValueError("graph revision must be non-negative")
        for value in (
            self.primary_confidence,
            self.human_probability,
            self.unknown_probability,
        ):
            if not 0.0 <= value <= 1.0:
                raise ValueError("routing probability must be between 0 and 1")
        if not 0.0 <= self.urgency_score <= 3.0:
            raise ValueError("urgency score must be between 0 and 3")
        if not self.input_digest.startswith("sha256:") or len(self.input_digest) != 71:
            raise ValueError("input digest must be sha256-prefixed")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["route_disposition"] = self.route_disposition.value
        for item in value["capabilities"]:
            item["disposition"] = item["disposition"].value
            item["supporting_fact_refs"] = list(item["supporting_fact_refs"])
        value["capabilities"] = list(value["capabilities"])
        value["reason_codes"] = list(value["reason_codes"])
        return value


@dataclass(frozen=True)
class ProviderDecision:
    provider: str
    model_version: str
    primary_intent: str
    intent_probabilities: dict[str, float]
    capability_probabilities: dict[str, float]
    human_probability: float
    urgency_score: float

    def __post_init__(self) -> None:
        unknown = set(self.capability_probabilities) - CAPABILITIES
        if unknown:
            raise ValueError(f"provider returned unknown capabilities: {sorted(unknown)}")
        values = [
            *self.intent_probabilities.values(),
            *self.capability_probabilities.values(),
            self.human_probability,
        ]
        if any(not 0.0 <= value <= 1.0 for value in values):
            raise ValueError("provider probabilities must be between 0 and 1")
        if not 0.0 <= self.urgency_score <= 3.0:
            raise ValueError("provider urgency score must be between 0 and 3")

    @property
    def primary_confidence(self) -> float:
        return self.intent_probabilities.get(self.primary_intent, 0.0)

    @property
    def unknown_probability(self) -> float:
        return self.intent_probabilities.get("unknown", 0.0)
