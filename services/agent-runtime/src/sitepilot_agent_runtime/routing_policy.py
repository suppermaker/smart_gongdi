"""Versioned deterministic policy applied to probabilistic router output."""

from __future__ import annotations

from dataclasses import dataclass

from sitepilot_agent_runtime.routing_contracts import (
    CapabilityDecision,
    CapabilityDisposition,
    ProviderDecision,
    RouteDisposition,
)


@dataclass(frozen=True)
class RoutingPolicyConfig:
    version: str = "routing-policy.v1"
    required_threshold: float = 0.80
    optional_threshold: float = 0.55
    human_threshold: float = 0.75
    unknown_threshold: float = 0.40
    minimum_primary_confidence: float = 0.55
    minimum_top_margin: float = 0.15


@dataclass(frozen=True)
class PolicyResult:
    capabilities: tuple[CapabilityDecision, ...]
    route_disposition: RouteDisposition
    reason_codes: tuple[str, ...]


class RoutingPolicy:
    def __init__(self, config: RoutingPolicyConfig | None = None) -> None:
        self.config = config or RoutingPolicyConfig()

    def apply(
        self,
        decision: ProviderDecision,
        fact_refs: tuple[str, ...] = (),
        *,
        high_risk: bool = False,
        closure_requested: bool = False,
        identity_resolved: bool = True,
    ) -> PolicyResult:
        probabilities = dict(decision.capability_probabilities)
        reasons: list[str] = []
        if high_risk:
            probabilities["regulation_retrieval"] = 1.0
            probabilities["conclusion_verification"] = 1.0
            reasons.append("HIGH_RISK_POLICY")
        if closure_requested:
            probabilities["closure_verification"] = 1.0
            reasons.append("CLOSURE_VERIFIER_REQUIRED")
        elif probabilities:
            probabilities["conclusion_verification"] = max(
                1.0, probabilities.get("conclusion_verification", 0.0)
            )
            reasons.append("CONCLUSION_VERIFIER_REQUIRED")

        capability_results = []
        for capability, probability in sorted(probabilities.items()):
            disposition = self._disposition(probability)
            if not identity_resolved and capability in {
                "certificate_verification",
                "permit_validation",
            }:
                disposition = CapabilityDisposition.SKIP
                reasons.append("IDENTITY_DEPENDENCY_BLOCKED")
            capability_results.append(
                CapabilityDecision(capability, probability, disposition, fact_refs)
            )

        ranked = sorted(decision.intent_probabilities.values(), reverse=True)
        margin = ranked[0] - ranked[1] if len(ranked) > 1 else ranked[0] if ranked else 0.0
        if decision.human_probability >= self.config.human_threshold:
            route = RouteDisposition.HUMAN_CLARIFICATION
            reasons.append("HUMAN_INPUT_REQUIRED")
        elif decision.unknown_probability >= self.config.unknown_threshold:
            route = (
                RouteDisposition.COLLECT_EVIDENCE
                if self._has_automatic_capability(capability_results)
                else RouteDisposition.HUMAN_ESCALATION
            )
            reasons.append("UNKNOWN_INTENT")
        elif decision.primary_confidence < self.config.minimum_primary_confidence:
            route = RouteDisposition.COLLECT_EVIDENCE
            reasons.append("LOW_PRIMARY_CONFIDENCE")
        elif margin < self.config.minimum_top_margin:
            route = RouteDisposition.COLLECT_EVIDENCE
            reasons.append("AMBIGUOUS_TOP_INTENTS")
        else:
            route = RouteDisposition.AUTO_ROUTE

        if route in {RouteDisposition.HUMAN_CLARIFICATION, RouteDisposition.HUMAN_ESCALATION}:
            capability_results.append(
                CapabilityDecision(
                    "human_clarification",
                    max(decision.human_probability, decision.unknown_probability),
                    CapabilityDisposition.REQUIRED,
                    fact_refs or ("system:routing-policy",),
                )
            )
        return PolicyResult(
            tuple(self._deduplicate(capability_results)), route, tuple(dict.fromkeys(reasons))
        )

    def _disposition(self, probability: float) -> CapabilityDisposition:
        if probability >= self.config.required_threshold:
            return CapabilityDisposition.REQUIRED
        if probability >= self.config.optional_threshold:
            return CapabilityDisposition.OPTIONAL_IF_BUDGET
        return CapabilityDisposition.SKIP

    @staticmethod
    def _has_automatic_capability(capabilities: list[CapabilityDecision]) -> bool:
        return any(
            item.capability != "human_clarification"
            and item.disposition != CapabilityDisposition.SKIP
            for item in capabilities
        )

    @staticmethod
    def _deduplicate(values: list[CapabilityDecision]) -> list[CapabilityDecision]:
        by_capability: dict[str, CapabilityDecision] = {}
        for value in values:
            existing = by_capability.get(value.capability)
            if existing is None or value.probability > existing.probability:
                by_capability[value.capability] = value
        return list(by_capability.values())
