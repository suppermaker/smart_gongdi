"""Minimal HTTP adapter for the Jev System One API."""

from __future__ import annotations

import asyncio
import json
import urllib.error
import urllib.request
from typing import Any

from sitepilot_agent_runtime.decision_provider import DecisionProviderError
from sitepilot_agent_runtime.routing_contracts import CAPABILITIES, ProviderDecision


class JevDecisionProvider:
    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str = "https://api.typesafe.ai",
        timeout_seconds: float = 5.0,
        max_attempts: int = 3,
    ) -> None:
        if not api_key:
            raise ValueError("JEV_API_KEY is required")
        if not model or model.endswith("latest"):
            raise ValueError("JEV_MODEL must be a fixed model version")
        self.api_key = api_key
        self.model = model
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max_attempts

    async def decide(self, state: dict[str, Any], questions: dict[str, Any]) -> ProviderDecision:
        body = json.dumps(
            {"model": self.model, "state": state, "questions": questions},
            separators=(",", ":"),
        ).encode()
        last_error: DecisionProviderError | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                raw = await asyncio.to_thread(self._post, body)
                return self._parse(raw)
            except DecisionProviderError as exc:
                last_error = exc
                if not exc.retryable or attempt == self.max_attempts:
                    raise
                await asyncio.sleep(0.05 * attempt)
        assert last_error is not None
        raise last_error

    def _post(self, body: bytes) -> dict[str, Any]:
        request = urllib.request.Request(
            f"{self.base_url}/v1/systemone",
            body,
            {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            retryable = exc.code == 429 or exc.code >= 500
            raise DecisionProviderError(
                "JEV_HTTP_ERROR", f"status={exc.code}", retryable=retryable
            ) from exc
        except (TimeoutError, urllib.error.URLError) as exc:
            raise DecisionProviderError("JEV_UNAVAILABLE", str(exc), retryable=True) from exc
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise DecisionProviderError("JEV_RESPONSE_INVALID", str(exc)) from exc

    def _parse(self, value: dict[str, Any]) -> ProviderDecision:
        try:
            answers = value["answers"]
            primary = answers["primary_intent"]
            intent_probabilities = {
                str(key): float(probability)
                for key, probability in primary["probabilities"].items()
            }
            primary_intent = str(
                primary.get("choice") or max(intent_probabilities, key=intent_probabilities.get)
            )
            capabilities = {
                capability: float(self._noul(answers[capability]))
                for capability in CAPABILITIES
                if capability in answers
            }
            human = float(self._noul(answers.get("needs_human", {"noul": 0.0})))
            urgency = float(answers.get("urgency", {}).get("score", 0.0))
            model_version = str(value.get("model") or self.model)
            return ProviderDecision(
                provider="jev",
                model_version=model_version,
                primary_intent=primary_intent,
                intent_probabilities=intent_probabilities,
                capability_probabilities=capabilities,
                human_probability=human,
                urgency_score=urgency,
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise DecisionProviderError("JEV_RESPONSE_INVALID", str(exc)) from exc

    @staticmethod
    def _noul(answer: Any) -> float:
        if isinstance(answer, (float, int)):
            return float(answer)
        if not isinstance(answer, dict):
            raise TypeError("noul answer must be an object or number")
        for key in ("noul", "probability", "score"):
            if key in answer:
                return float(answer[key])
        raise KeyError("noul")
