# Contracts

This directory will contain versioned JSON Schemas and examples shared by Java and Python.

Initial contract families:

- `event/v1`: normalized worksite signal and source provenance;
- `investigation-task/v1`: task identity, objective and budgets;
- `hypothesis-graph/v1`: hypotheses, evidence, questions, conflicts and actions;
- `graph-delta/v1`: isolated sub-agent output;
- `delegation/v1`: dynamic DAG task contract;
- `tool-result/v1`: typed MCP observation envelope;
- `action-proposal/v1`: bounded action and verification criteria;
- `evaluation-run/v1`: reproducible scenario result.

Contracts are written before service-specific DTOs. Examples are executable fixtures for cross-language tests.
