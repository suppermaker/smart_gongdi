from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any, Literal

import jsonschema
import pytest
import yaml
from pydantic import BaseModel, ConfigDict, Field

ROOT = Path(__file__).resolve().parents[1]
ROOT = Path(__file__).resolve().parents[3]
CONTRACTS = ROOT / "contracts"
SCENARIOS = ROOT / "services" / "scenario-gym" / "scenarios"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Subject(StrictModel):
    type: str
    id: str
    display_name: str | None = None


class Source(StrictModel):
    source_type: Literal[
        "simulator", "business_service", "sensor", "camera_analysis", "document", "human"
    ]
    source_ref: str
    source_version: str


class WorksiteEvent(StrictModel):
    schema_version: Literal["1.0"]
    event_id: str
    event_type: str
    subject: Subject
    occurred_at: datetime
    ingested_at: datetime
    source: Source
    attributes: dict[str, Any]
    correlation_id: str | None = None
    aggregate_version: int | None = Field(default=None, ge=0)


class Budget(StrictModel):
    max_model_calls: int = Field(ge=0, le=200)
    max_tool_calls: int = Field(ge=1, le=1000)
    max_sub_agents: int = Field(ge=0, le=32)
    max_graph_revisions: int = Field(ge=1, le=500)
    timeout_seconds: int = Field(ge=1, le=86400)
    max_cost_usd: float | None = Field(default=None, ge=0)


class InvestigationTask(StrictModel):
    schema_version: Literal["1.0"]
    task_id: str
    goal: str = Field(min_length=5)
    risk_ref: str
    status: str
    created_at: datetime
    deadline: datetime | None = None
    graph_revision: int = Field(ge=0)
    budgets: Budget
    pending_human_request_id: str | None = None


class ToolResult(StrictModel):
    schema_version: Literal["1.0"]
    tool_call_id: str
    tool_name: str
    status: str
    observed_at: datetime
    source_version: str
    completeness: str
    payload: Any
    error: dict[str, Any] | None = None


def example_paths() -> list[Path]:
    return sorted(CONTRACTS.glob("*/examples/valid.json"))


def test_contract_examples_are_json_and_versioned() -> None:
    paths = example_paths()
    assert len(paths) >= 8
    for path in paths:
        value = json.loads(path.read_text(encoding="utf-8"))
        assert value["schema_version"] == "1.0"


def test_event_and_task_examples_parse_as_strong_types() -> None:
    WorksiteEvent.model_validate(
        json.loads((CONTRACTS / "event/examples/valid.json").read_text(encoding="utf-8"))
    )
    InvestigationTask.model_validate(
        json.loads(
            (CONTRACTS / "investigation-task/examples/valid.json").read_text(encoding="utf-8")
        )
    )


def test_tool_results_in_all_scenarios_parse_as_strong_types() -> None:
    for path in sorted(SCENARIOS.glob("*/tools.yaml")):
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
        for tool in document["tools"]:
            for response in tool["responses"]:
                result = ToolResult.model_validate(response["result"])
                assert result.tool_name == tool["name"]


def test_public_scenarios_do_not_contain_private_directory_or_markers() -> None:
    for path in sorted(SCENARIOS.glob("*/*.yaml")):
        assert "private" not in path.parts
        text = path.read_text(encoding="utf-8").lower()
        assert "ground_truth" not in text
        assert "root_causes" not in text


def test_investigation_routing_contract_accepts_examples_and_rejects_invalid_values() -> None:
    schema = json.loads(
        (CONTRACTS / "investigation-routing/v1.schema.json").read_text(encoding="utf-8")
    )
    for name in ("valid.json", "unknown.json", "human-escalation.json"):
        value = json.loads(
            (CONTRACTS / "investigation-routing/examples" / name).read_text(encoding="utf-8")
        )
        jsonschema.validate(value, schema)

    invalid_capability = json.loads(
        (CONTRACTS / "investigation-routing/examples/valid.json").read_text(encoding="utf-8")
    )
    invalid_capability["capabilities"][0]["capability"] = "arbitrary_model_tool"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(invalid_capability, schema)

    invalid_probability = json.loads(
        (CONTRACTS / "investigation-routing/examples/valid.json").read_text(encoding="utf-8")
    )
    invalid_probability["unknown_probability"] = 1.1
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(invalid_probability, schema)

    missing_provenance = json.loads(
        (CONTRACTS / "investigation-routing/examples/valid.json").read_text(encoding="utf-8")
    )
    del missing_provenance["capabilities"][0]["supporting_fact_refs"]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(missing_provenance, schema)


def test_context_pack_examples_are_versioned_and_strict() -> None:
    schemas = {
        "1.0": json.loads(
            (CONTRACTS / "context-pack/v1.schema.json").read_text(encoding="utf-8")
        ),
        "2.0": json.loads(
            (CONTRACTS / "context-pack/v2.schema.json").read_text(encoding="utf-8")
        ),
    }
    examples = sorted((CONTRACTS / "context-pack/examples").glob("*.json"))
    assert len(examples) == 5
    for path in examples:
        value = json.loads(path.read_text(encoding="utf-8"))
        jsonschema.validate(value, schemas[value["schema_version"]])
    schema = schemas["1.0"]

    invalid_role = json.loads(
        (CONTRACTS / "context-pack/examples/valid.json").read_text(encoding="utf-8")
    )
    invalid_role["role"] = "model_selected_admin"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(invalid_role, schema)

    missing_manifest = json.loads(
        (CONTRACTS / "context-pack/examples/valid.json").read_text(encoding="utf-8")
    )
    del missing_manifest["manifest"]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(missing_manifest, schema)

    invalid_digest = json.loads(
        (CONTRACTS / "context-pack/examples/valid.json").read_text(encoding="utf-8")
    )
    invalid_digest["manifest"]["context_digest"] = "not-a-digest"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(invalid_digest, schema)

    invalid_parent = json.loads(
        (CONTRACTS / "context-pack/examples/v2-valid.json").read_text(encoding="utf-8")
    )
    invalid_parent["context_mode"] = "DELTA"
    invalid_parent["parent_context"] = None
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(invalid_parent, schemas["2.0"])
