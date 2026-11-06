import urllib.error

import pytest

from sitepilot_agent_runtime.decision_provider import DecisionProviderError
from sitepilot_agent_runtime.jev_provider import JevDecisionProvider


def provider() -> JevDecisionProvider:
    return JevDecisionProvider("test-key", "jev-1.14.0", "https://jev.invalid", max_attempts=2)


@pytest.mark.asyncio
async def test_jev_parses_choice_noul_and_score(monkeypatch):
    value = {
        "model": "jev-1.14.0",
        "answers": {
            "primary_intent": {
                "choice": "zone_connectivity_failure",
                "probabilities": {
                    "zone_connectivity_failure": 0.78,
                    "equipment_local_failure": 0.17,
                    "unknown": 0.05,
                },
            },
            "network_diagnosis": {"noul": 0.91},
            "equipment_observation": 0.72,
            "needs_human": {"noul": 0.08},
            "urgency": {"score": 1.8},
        },
    }
    item = provider()
    monkeypatch.setattr(item, "_post", lambda body: value)
    result = await item.decide({}, {})
    assert result.primary_intent == "zone_connectivity_failure"
    assert result.capability_probabilities["network_diagnosis"] == 0.91
    assert result.human_probability == 0.08
    assert result.urgency_score == 1.8


@pytest.mark.asyncio
async def test_jev_retries_429_and_rejects_malformed_response(monkeypatch):
    item = provider()
    attempts = 0

    def temporary_failure(body):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise DecisionProviderError("JEV_HTTP_ERROR", "429", retryable=True)
        return {
            "answers": {
                "primary_intent": {
                    "choice": "unknown",
                    "probabilities": {"unknown": 1.0},
                }
            }
        }

    monkeypatch.setattr(item, "_post", temporary_failure)
    assert (await item.decide({}, {})).primary_intent == "unknown"
    assert attempts == 2

    monkeypatch.setattr(item, "_post", lambda body: {"answers": {}})
    with pytest.raises(DecisionProviderError, match="JEV_RESPONSE_INVALID"):
        await item.decide({}, {})


def test_jev_requires_key_and_fixed_model():
    with pytest.raises(ValueError, match="JEV_API_KEY"):
        JevDecisionProvider("", "jev-1.14.0")
    with pytest.raises(ValueError, match="fixed model"):
        JevDecisionProvider("key", "jev-latest")


def test_http_error_retryability(monkeypatch):
    item = provider()

    def fail(request, timeout):
        raise urllib.error.HTTPError(request.full_url, 401, "unauthorized", {}, None)

    monkeypatch.setattr("urllib.request.urlopen", fail)
    with pytest.raises(DecisionProviderError) as error:
        item._post(b"{}")
    assert error.value.retryable is False
