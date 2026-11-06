"""Explicit synthetic Jev connectivity smoke test; not part of default tests."""

import asyncio
import json
import os

from sitepilot_agent_runtime.intent_router import routing_questions
from sitepilot_agent_runtime.jev_provider import JevDecisionProvider


async def main() -> None:
    api_key = os.environ.get("JEV_API_KEY")
    model = os.environ.get("JEV_MODEL")
    if not api_key or not model:
        raise SystemExit("JEV_API_KEY and fixed JEV_MODEL are required")
    provider = JevDecisionProvider(
        api_key,
        model,
        os.getenv("JEV_BASE_URL", "https://api.typesafe.ai"),
    )
    result = await provider.decide(
        {
            "event": {
                "event_type": "equipment.offline",
                "subject_type": "tower-crane",
                "attributes": {"offline_duration_seconds": 300, "zone": "synthetic-zone"},
            },
            "fact_refs": ["event:event_type", "event:attributes"],
        },
        routing_questions(),
    )
    print(
        json.dumps(
            {
                "provider": result.provider,
                "model_version": result.model_version,
                "primary_intent": result.primary_intent,
                "capability_count": len(result.capability_probabilities),
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
