"""Validate SitePilot M0 contracts and deterministic Scenario Gym fixtures."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"
SCENARIOS = Path(__file__).resolve().parent / "scenarios"


def load_json(path: Path) -> Any:
    try:
        return yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise ValueError(f"invalid YAML document: {path}: {exc}") from exc


def validate_instance(schema_path: Path, instance_path: Path) -> list[str]:
    schema = load_json(schema_path)
    instance = load_json(instance_path)
    try:
        import jsonschema  # type: ignore
    except ImportError:
        return structural_checks(schema, instance, instance_path)

    validator = jsonschema.Draft202012Validator(
        schema,
        format_checker=jsonschema.FormatChecker(),
    )
    return [
        f"{instance_path}: {error.message} at {list(error.path)}"
        for error in sorted(validator.iter_errors(instance), key=str)
    ]


def structural_checks(schema: dict[str, Any], instance: Any, path: Path) -> list[str]:
    """Minimal fallback when jsonschema is not installed."""
    if not isinstance(instance, dict):
        return [f"{path}: root must be an object"]
    errors: list[str] = []
    for field in schema.get("required", []):
        if field not in instance:
            errors.append(f"{path}: missing required field {field}")
    for field, rule in schema.get("properties", {}).items():
        if field not in instance or not isinstance(rule, dict):
            continue
        value = instance[field]
        if "const" in rule and value != rule["const"]:
            errors.append(f"{path}: {field} must equal {rule['const']}")
        expected = rule.get("type")
        if expected == "string" and not isinstance(value, str):
            errors.append(f"{path}: {field} must be a string")
        if expected == "integer" and (not isinstance(value, int) or isinstance(value, bool)):
            errors.append(f"{path}: {field} must be an integer")
        if expected == "array" and not isinstance(value, list):
            errors.append(f"{path}: {field} must be an array")
    return errors


def validate_contracts() -> list[str]:
    errors: list[str] = []
    schema_paths = sorted(CONTRACTS.glob("*/v1.schema.json"))
    if not schema_paths:
        return [f"{CONTRACTS}: no schemas found"]
    for schema_path in schema_paths:
        examples = [schema_path.parent / "examples" / "valid.json"]
        if not examples:
            errors.append(f"{schema_path}: no examples found")
            continue
        for example in examples:
            errors.extend(validate_instance(schema_path, example))
    v2_schema = CONTRACTS / "evaluation-run/v2.schema.json"
    v2_example = CONTRACTS / "evaluation-run/examples/valid-v2.json"
    if v2_schema.is_file() and v2_example.is_file():
        errors.extend(validate_instance(v2_schema, v2_example))
    return errors


def public_fixture_files(scenario_dir: Path) -> list[Path]:
    return sorted(
        path
        for path in scenario_dir.rglob("*")
        if path.is_file() and path.suffix in {".json", ".yaml"} and "private" not in path.parts
    )


def require_fields(document: Any, fields: list[str], label: str) -> list[str]:
    if not isinstance(document, dict):
        return [f"{label}: root must be an object"]
    return [f"{label}: missing {field}" for field in fields if field not in document]


def validate_tool_result(tool_name: str, result: Any, label: str) -> list[str]:
    errors = require_fields(
        result,
        [
            "schema_version",
            "tool_call_id",
            "tool_name",
            "status",
            "observed_at",
            "source_version",
            "completeness",
            "payload",
        ],
        label,
    )
    if isinstance(result, dict) and result.get("tool_name") != tool_name:
        errors.append(f"{label}: tool_name does not match {tool_name}")
    return errors


def validate_scenario(scenario_dir: Path) -> list[str]:
    errors: list[str] = []
    scenario_path = scenario_dir / "scenario.yaml"
    tools_path = scenario_dir / "tools.yaml"
    assertions_path = scenario_dir / "assertions.yaml"
    hidden_path = scenario_dir / "private" / "ground_truth.yaml"
    required_paths = [scenario_path, tools_path, assertions_path, hidden_path]
    for path in required_paths:
        if not path.is_file():
            errors.append(f"{scenario_dir}: missing {path.relative_to(scenario_dir)}")
    if errors:
        return errors

    try:
        manifest = load_json(scenario_path)
        tools_doc = load_json(tools_path)
        assertions = load_json(assertions_path)
        hidden = load_json(hidden_path)
    except ValueError as exc:
        return [str(exc)]

    errors.extend(validate_instance(CONTRACTS / "scenario-manifest/v1.schema.json", scenario_path))
    errors.extend(require_fields(tools_doc, ["schema_version", "tools"], str(tools_path)))
    errors.extend(
        require_fields(
            assertions,
            [
                "schema_version",
                "required_tool_groups",
                "required_evidence_categories",
                "acceptable_action_types",
                "forbidden_action_types",
            ],
            str(assertions_path),
        )
    )
    errors.extend(
        require_fields(
            hidden,
            ["schema_version", "scenario_id", "root_causes", "required_facts"],
            str(hidden_path),
        )
    )

    if (
        isinstance(manifest, dict)
        and isinstance(hidden, dict)
        and manifest.get("scenario_id") != hidden.get("scenario_id")
    ):
        errors.append(f"{scenario_dir}: manifest and ground truth scenario_id differ")

    initial_event = scenario_dir / str(manifest.get("initial_event", ""))
    if not initial_event.is_file():
        errors.append(f"{scenario_dir}: initial_event does not resolve")
    else:
        errors.extend(validate_instance(CONTRACTS / "event/v1.schema.json", initial_event))

    tools = tools_doc.get("tools", []) if isinstance(tools_doc, dict) else []
    tool_names: set[str] = set()
    for index, tool in enumerate(tools):
        label = f"{tools_path}: tools[{index}]"
        if not isinstance(tool, dict) or not isinstance(tool.get("name"), str):
            errors.append(f"{label}: missing name")
            continue
        name = tool["name"]
        if name in tool_names:
            errors.append(f"{label}: duplicate tool name {name}")
        tool_names.add(name)
        roles = tool.get("roles")
        if not isinstance(roles, list) or not roles:
            errors.append(f"{label}: roles must be non-empty")
        responses = tool.get("responses")
        if not isinstance(responses, list) or not responses:
            errors.append(f"{label}: responses must be non-empty")
            continue
        for response_index, response in enumerate(responses):
            result = response.get("result") if isinstance(response, dict) else None
            errors.extend(
                validate_tool_result(
                    name,
                    result,
                    f"{label}.responses[{response_index}].result",
                )
            )

    available_tools = set(manifest.get("available_tools", []))
    if available_tools != tool_names:
        errors.append(
            f"{scenario_dir}: manifest tools and tools.yaml differ: "
            f"manifest_only={sorted(available_tools - tool_names)}, "
            f"tools_only={sorted(tool_names - available_tools)}"
        )

    for group in assertions.get("required_tool_groups", []):
        if not isinstance(group, list) or not group:
            errors.append(f"{assertions_path}: required_tool_groups contains empty group")
        elif not any(name in tool_names for name in group):
            errors.append(f"{assertions_path}: no tool satisfies required group {group}")

    public_text = "\n".join(
        path.read_text(encoding="utf-8") for path in public_fixture_files(scenario_dir)
    )
    secrets = [
        *hidden.get("root_causes", []),
        *hidden.get("accepted_conclusions", []),
        *hidden.get("required_facts", []),
    ]
    for secret in secrets:
        if isinstance(secret, str) and secret and secret.lower() in public_text.lower():
            errors.append(f"{scenario_dir}: hidden truth leaked into public fixture: {secret}")
    if re.search(r"hidden_answer|root_causes|ground_truth", public_text, re.IGNORECASE):
        errors.append(f"{scenario_dir}: hidden-truth marker leaked into public fixture")
    return errors


def validate_scenarios() -> list[str]:
    if not SCENARIOS.is_dir():
        return [f"{SCENARIOS}: scenario directory does not exist"]
    scenario_dirs = sorted(path for path in SCENARIOS.iterdir() if path.is_dir())
    errors: list[str] = []
    if len(scenario_dirs) != 3:
        errors.append(f"expected exactly 3 scenarios, found {len(scenario_dirs)}")
    fault_types: set[str] = set()
    for scenario_dir in scenario_dirs:
        errors.extend(validate_scenario(scenario_dir))
        variants_dir = scenario_dir / "variants"
        variants = (
            sorted(path for path in variants_dir.iterdir() if path.is_dir())
            if variants_dir.is_dir()
            else []
        )
        if len(variants) != 4:
            errors.append(f"{scenario_dir}: expected normal + 3 adversarial variants")
        if not any(path.name == "normal" for path in variants):
            errors.append(f"{scenario_dir}: missing normal variant")
        for variant in variants:
            for relative in (
                "variant.yaml",
                "assertions.yaml",
                "faults.yaml",
                "private/ground_truth.yaml",
            ):
                if not (variant / relative).is_file():
                    errors.append(f"{variant}: missing {relative}")
            if (variant / "faults.yaml").is_file():
                document = load_json(variant / "faults.yaml")
                fault_types.update(item.get("type", "") for item in document.get("faults", []))
    required_faults = {
        "identity_ambiguity",
        "evidence_conflict",
        "stale_evidence",
        "tool_timeout",
        "duplicate_event",
        "interruption",
        "unauthorized_action",
        "fake_remediation",
    }
    if not required_faults <= fault_types:
        errors.append(f"scenario variants missing faults: {sorted(required_faults - fault_types)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["contracts", "scenarios", "all"])
    args = parser.parse_args()
    errors: list[str] = []
    if args.command in {"contracts", "all"}:
        errors.extend(validate_contracts())
    if args.command in {"scenarios", "all"}:
        errors.extend(validate_scenarios())
    if errors:
        print("VALIDATION_FAILED")
        for error in errors:
            print(f"- {error}")
        return 1
    print(f"VALIDATION_OK command={args.command}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
