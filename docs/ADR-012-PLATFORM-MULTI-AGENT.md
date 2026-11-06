# ADR-012: Contract-aligned multi-agent application platform

## Status

Accepted.

## Decision

SitePilot is repositioned from a standalone risk-investigation product to the multi-agent orchestration and governance layer of the contracted smart-worksite large-model application platform.

The platform exposes one task entry point and routes work to specialist Agents:

- Knowledge Agent: project-isolated RAG and citations;
- Database Agent: schema-aware, permission-scoped read-only queries;
- Review Agent: document compliance review against versioned standards;
- Report Agent: template-constrained multi-source report generation;
- OCR Agent: document classification and structured field extraction;
- Risk Investigation Agent: the existing HEA Graph/Temporal investigation system;
- Synthesis Agent: combines specialist outputs without inventing facts;
- Verifier Agent: checks citations, permissions, completeness and export constraints.

The Supervisor produces a bounded DAG. Independent Knowledge and Database tasks may run in parallel; synthesis and verification depend on their outputs. Every specialist receives only server-approved tools. PostgreSQL remains business authority and Temporal remains execution authority.

## Scope exception

The platform intentionally does not implement pretraining, LoRA/QLoRA, PPO or training-data pipelines. Domain adaptation uses local foundation models, RAG, structured prompting, tools, deterministic policies and evaluation. This exception must be documented in the delivery scope.
