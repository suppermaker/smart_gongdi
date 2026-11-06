# Metrics Catalogue

## Platform service metrics

- `sitepilot.platform.operations`: counter tagged only with `stage`, `operation`, and `outcome=success|error`.
- `sitepilot.platform.operation.duration`: timer tagged only with `stage` and `operation`.

Covered stages include PlatformTaskService, PlatformExecutionService, ConversationService, KnowledgeService, DatabaseQaService, ContractApplicationService, ControlPlaneService, GraphMergeService, ActionService and McpToolService. This yields route, RAG, SQL plan/execution, review, report, OCR, conversation and risk-bridge measurements without recording prompts, SQL text, document text, credentials or tokens.

## Existing context metrics

Context Assembly publishes build, selected-node, estimated-token, budget-utilization, reuse and lineage metrics. HTTP/JVM metrics are provided by Spring Actuator and scraped by Prometheus.

Trace attributes are limited to tenant, project, correlation id and stage. Raw payloads and authorization material are prohibited. `ContractModulesEndToEndTest` proves specialist metrics are emitted; `TraceChainTest` proves trace continuity and sensitive-attribute exclusion.
