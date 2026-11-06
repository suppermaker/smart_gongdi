"""Live Jev evaluation on realistic synthetic worksite incidents."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

import yaml
from sitepilot_agent_runtime.intent_router import routing_questions
from sitepilot_agent_runtime.jev_provider import JevDecisionProvider
from sitepilot_agent_runtime.routing_policy import RoutingPolicy

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def load_cases(path: Path) -> list[dict[str, Any]]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    if document.get("schema_version") != "1.0":
        raise ValueError("unsupported case schema")
    return document["cases"]


async def evaluate_case(
    provider: JevDecisionProvider,
    policy: RoutingPolicy,
    case: dict[str, Any],
    semaphore: asyncio.Semaphore,
) -> dict[str, Any]:
    async with semaphore:
        started = time.perf_counter()
        result = await provider.decide(case["state"], routing_questions())
        latency_ms = (time.perf_counter() - started) * 1000

    probabilities = result.capability_probabilities
    single = {max(probabilities, key=probabilities.get)} if probabilities else set()
    multi = {capability for capability, probability in probabilities.items() if probability >= 0.55}
    if result.human_probability >= 0.75:
        multi.add("human_clarification")
    policy_result = policy.apply(
        result,
        tuple(str(value) for value in case["state"].get("fact_refs", ())),
        identity_resolved=not case["expected_human"],
        closure_requested="closure_verification" in case["required_capabilities"],
    )
    compiled = {
        item.capability for item in policy_result.capabilities if item.disposition != "SKIP"
    }
    expected = set(case["required_capabilities"])
    return {
        "case_id": case["case_id"],
        "title": case["title"],
        "model_version": result.model_version,
        "latency_ms": round(latency_ms, 3),
        "primary_intent": result.primary_intent,
        "expected_primary_intent": case["expected_primary_intent"],
        "intent_correct": float(result.primary_intent == case["expected_primary_intent"]),
        "primary_confidence": result.primary_confidence,
        "intent_probabilities": result.intent_probabilities,
        "capability_probabilities": result.capability_probabilities,
        "human_probability": result.human_probability,
        "expected_human": case["expected_human"],
        "human_correct": float((result.human_probability >= 0.75) == case["expected_human"]),
        "urgency_score": result.urgency_score,
        "urgency_min": case["urgency_min"],
        "urgency_threshold_met": float(result.urgency_score >= float(case["urgency_min"])),
        "expected_capabilities": sorted(expected),
        "single_choice": score_selection(single, expected),
        "multi_label": score_selection(multi, expected),
        "policy_compiled": score_selection(compiled, expected),
        "route_disposition": policy_result.route_disposition,
        "reason_codes": list(policy_result.reason_codes),
    }


def score_selection(selected: set[str], expected: set[str]) -> dict[str, Any]:
    true_positive = len(selected & expected)
    precision = true_positive / len(selected) if selected else 0.0
    recall = true_positive / len(expected) if expected else 1.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "selected": sorted(selected),
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "missing": sorted(expected - selected),
        "extra": sorted(selected - expected),
    }


def aggregate(rows: list[dict[str, Any]], mode: str) -> dict[str, float]:
    latencies = sorted(float(row["latency_ms"]) for row in rows)
    return {
        "intent_top1_accuracy": mean(row["intent_correct"] for row in rows),
        "capability_precision": mean(row[mode]["precision"] for row in rows),
        "capability_recall": mean(row[mode]["recall"] for row in rows),
        "capability_f1": mean(row[mode]["f1"] for row in rows),
        "human_escalation_accuracy": mean(row["human_correct"] for row in rows),
        "urgency_threshold_accuracy": mean(row["urgency_threshold_met"] for row in rows),
        "avg_latency_ms": mean(latencies),
        "p50_latency_ms": percentile(latencies, 0.50),
        "p95_latency_ms": percentile(latencies, 0.95),
    }


def mean(values) -> float:
    values = list(values)
    return round(statistics.fmean(values), 6) if values else 0.0


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    index = max(0, min(len(values) - 1, int((len(values) - 1) * quantile + 0.999999)))
    return round(values[index], 6)


def digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def discover_models(base_url: str, api_key: str) -> dict[str, Any]:
    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/models",
        headers={"Authorization": f"Bearer {api_key}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode(errors="replace")[:1000]
        raise RuntimeError(f"JEV_MODEL_DISCOVERY_HTTP_{exc.code}: {body}") from exc


async def run(args) -> dict[str, Any]:
    api_key = os.environ.get("JEV_API_KEY", "")
    if not api_key:
        raise SystemExit("JEV_API_KEY is required")
    model = args.model or os.environ.get("JEV_MODEL", "")
    if not model:
        models = discover_models(args.base_url, api_key)
        available = [str(item["id"]) for item in models.get("data", [])]
        raise SystemExit("JEV_MODEL is required; available=" + ",".join(available))
    cases = load_cases(args.cases)
    provider = JevDecisionProvider(
        api_key,
        model,
        args.base_url,
        timeout_seconds=args.timeout,
        max_attempts=3,
    )
    semaphore = asyncio.Semaphore(args.concurrency)
    started = time.time()
    rows = await asyncio.gather(
        *(evaluate_case(provider, RoutingPolicy(), case, semaphore) for case in cases)
    )
    return {
        "schema_version": "1.0",
        "run_type": "jev-live-realistic-synthetic",
        "case_count": len(cases),
        "model": model,
        "temperature": None,
        "duration_seconds": round(time.time() - started, 3),
        "single_choice": aggregate(rows, "single_choice"),
        "multi_label": aggregate(rows, "multi_label"),
        "policy_compiled": aggregate(rows, "policy_compiled"),
        "cases": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("JEV_BASE_URL", "https://api.typesafe.ai"))
    parser.add_argument("--model", default=os.getenv("JEV_MODEL", ""))
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--cases", type=Path, default=HERE / "jev_complex_cases.yaml")
    parser.add_argument(
        "--output", type=Path, default=ROOT / "artifacts/evaluations/jev-live-realistic"
    )
    args = parser.parse_args()
    try:
        result = asyncio.run(run(args))
    except RuntimeError as exc:
        args.output.mkdir(parents=True, exist_ok=True)
        blocker = {
            "schema_version": "1.0",
            "status": "BLOCKED",
            "reason": str(exc),
            "base_url": args.base_url,
            "case_count": len(load_cases(args.cases)),
        }
        (args.output / "blocker.json").write_text(
            json.dumps(blocker, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        print(json.dumps(blocker, sort_keys=True))
        return 2
    args.output.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False).encode() + b"\n"
    (args.output / "results.json").write_bytes(payload)
    summary = {
        "case_count": result["case_count"],
        "model": result["model"],
        "single_choice": result["single_choice"],
        "multi_label": result["multi_label"],
        "policy_compiled": result["policy_compiled"],
        "artifact_digest": digest(payload),
    }
    (args.output / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
