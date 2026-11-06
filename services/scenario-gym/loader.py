"""Versioned Scenario Gym fixture loading with a strict private-answer boundary."""

from __future__ import annotations

import copy
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


@dataclass(frozen=True)
class ScenarioVariant:
    scenario_dir: Path
    variant_dir: Path
    manifest: dict[str, Any]
    initial_event: dict[str, Any]
    tools: dict[str, Any]
    assertions: dict[str, Any]
    faults: dict[str, Any]

    @property
    def scenario_id(self) -> str:
        return str(self.manifest["scenario_id"])

    @property
    def variant_id(self) -> str:
        return str(self.manifest["variant_id"])

    def load_hidden_truth(self) -> dict[str, Any]:
        """Load oracle data only for the evaluator, never runtime context."""
        return load_document(self.variant_dir / "private" / "ground_truth.yaml")

    def public_runtime_context(self) -> dict[str, Any]:
        return {
            "event": copy.deepcopy(self.initial_event),
            "scenario": copy.deepcopy(self.manifest),
            "tools": copy.deepcopy(self.tools),
            "faults": copy.deepcopy(self.faults),
            "fact_refs": ["event:event_type", "event:subject", "event:attributes"],
        }


def load_document(path: Path) -> Any:
    try:
        return yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise ValueError(f"invalid YAML document: {path}: {exc}") from exc


def load_variant(scenario_dir: Path, variant_id: str) -> ScenarioVariant:
    variant_dir = scenario_dir / "variants" / variant_id
    base = load_document(scenario_dir / "scenario.yaml")
    variant = load_document(variant_dir / "variant.yaml")
    manifest = copy.deepcopy(base)
    variant_event = variant.pop("initial_event_override", None)
    manifest.update(variant)
    manifest["base_scenario_id"] = base["scenario_id"]
    manifest["scenario_id"] = base["scenario_id"]
    manifest["variant_id"] = variant_id
    initial_event = load_document(scenario_dir / str(base["initial_event"]))
    if variant_event:
        initial_event.update(variant_event)
    return ScenarioVariant(
        scenario_dir=scenario_dir,
        variant_dir=variant_dir,
        manifest=manifest,
        initial_event=initial_event,
        tools=load_document(scenario_dir / "tools.yaml"),
        assertions=load_document(variant_dir / "assertions.yaml"),
        faults=load_document(variant_dir / "faults.yaml"),
    )


def discover_variants(root: Path) -> list[ScenarioVariant]:
    variants: list[ScenarioVariant] = []
    for scenario_dir in sorted(path for path in root.iterdir() if path.is_dir()):
        for variant_dir in sorted((scenario_dir / "variants").iterdir()):
            if variant_dir.is_dir():
                variants.append(load_variant(scenario_dir, variant_dir.name))
    return variants
