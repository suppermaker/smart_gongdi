"""Provider boundary for low-cost probabilistic routing decisions."""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable
from typing import Any, Protocol

from sitepilot_agent_runtime.routing_contracts import ProviderDecision


class DecisionProvider(Protocol):
    async def decide(
        self, state: dict[str, Any], questions: dict[str, Any]
    ) -> ProviderDecision: ...


class DecisionProviderError(RuntimeError):
    def __init__(self, code: str, detail: str, *, retryable: bool = False) -> None:
        self.code = code
        self.retryable = retryable
        super().__init__(f"{code}: {detail}")


class DeterministicDecisionProvider:
    def __init__(self, decision: ProviderDecision) -> None:
        self.decision = decision
        self.calls = 0

    async def decide(self, state: dict[str, Any], questions: dict[str, Any]) -> ProviderDecision:
        self.calls += 1
        return ProviderDecision(**json.loads(json.dumps(self.decision.__dict__)))


class RuleBasedDecisionProvider:
    """Offline provider used by tests, demos and safe provider fallback."""

    async def decide(self, state: dict[str, Any], questions: dict[str, Any]) -> ProviderDecision:
        from sitepilot_agent_runtime.intent_router import deterministic_safe_route

        if decision := deterministic_safe_route(state):
            return decision
        return ProviderDecision(
            provider="deterministic",
            model_version="event-map-v1",
            primary_intent="unknown",
            intent_probabilities={"unknown": 1.0},
            capability_probabilities={"human_clarification": 1.0},
            human_probability=1.0,
            urgency_score=1.0,
        )


class FallbackDecisionProvider:
    """Uses a cached decision, then a deterministic safe route, then human escalation."""

    def __init__(
        self,
        primary: DecisionProvider,
        cached: Callable[[dict[str, Any]], Awaitable[ProviderDecision | None]],
        deterministic: Callable[[dict[str, Any]], ProviderDecision | None],
    ) -> None:
        self.primary = primary
        self.cached = cached
        self.deterministic = deterministic
        self.last_fallback_reason: str | None = None

    async def decide(self, state: dict[str, Any], questions: dict[str, Any]) -> ProviderDecision:
        try:
            return await self.primary.decide(state, questions)
        except DecisionProviderError as exc:
            self.last_fallback_reason = exc.code
            if existing := await self.cached(state):
                return existing
            if safe := self.deterministic(state):
                return ProviderDecision(
                    provider="fallback",
                    model_version=safe.model_version,
                    primary_intent=safe.primary_intent,
                    intent_probabilities=safe.intent_probabilities,
                    capability_probabilities=safe.capability_probabilities,
                    human_probability=safe.human_probability,
                    urgency_score=safe.urgency_score,
                )
            return ProviderDecision(
                provider="fallback",
                model_version="router-unavailable-v1",
                primary_intent="unknown",
                intent_probabilities={"unknown": 1.0},
                capability_probabilities={"human_clarification": 1.0},
                human_probability=1.0,
                urgency_score=2.0,
            )
