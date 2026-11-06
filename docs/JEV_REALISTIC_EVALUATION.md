# Jev Realistic Routing Evaluation

## Purpose

Evaluate Jev as the System One router on realistic but synthetic worksite incidents. The dataset is designed to test multi-label capability selection, open-set rejection, ambiguity handling, urgency scoring and human escalation without sending real worker or project data.

## Dataset

File: `services/scenario-gym/jev_complex_cases.yaml`

The 18 cases cover:

- partial zone-network outage versus local equipment failure;
- maintenance-window boundary conflicts;
- two-candidate worker identity ambiguity;
- badge, face and shift-roster conflicts;
- permit expiry across midnight;
- incomplete certificate registries where `not_found` is not proof of absence;
- reused remediation photographs and mismatched finding IDs;
- successful remediation API calls without current observations;
- stale evidence conflicting with current site observations;
- overload alarms mixed with sensor calibration drift;
- geofence alerts with GPS uncertainty and camera corroboration;
- low-confidence PPE detection;
- regulation-version conflicts;
- duplicate events during an active investigation;
- healthy collectors with unreachable gateways;
- observations outside the verification window;
- unknown vendor events containing prompt-injection text.

All worker, equipment, permit and finding identifiers are synthetic.

## Metrics

The live Harness compares three interpretations of the same Jev response:

1. `single_choice`: select only the highest-probability capability;
2. `multi_label`: select every capability with probability at least 0.55;
3. `policy_compiled`: apply SitePilot's versioned RoutingPolicy.

Metrics:

- intent Top-1 accuracy;
- capability precision, recall and F1;
- human-escalation accuracy;
- urgency-threshold accuracy;
- average and P95 latency;
- per-case intent and capability probabilities;
- missing and extra capabilities.

## Run

```bash
export JEV_API_KEY='...'
export JEV_MODEL='<fixed model version>'
make jev-live-evaluate
```

The key is environment-only and is never written to evaluation artifacts.

## Current blocker

Both the SitePilot server and the current workstation received HTTP `451` from the official TypeSafe API:

```text
Typesafe is not available in your region.
```

Artifact: `artifacts/evaluations/jev-live-realistic/blocker.json`

Therefore no Jev accuracy or latency number is currently reported. Run the same command from a TypeSafe-supported egress region to produce `results.json` and `summary.json`.
