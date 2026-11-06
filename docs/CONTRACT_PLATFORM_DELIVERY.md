# Contract Platform Delivery Guide

## Product boundary

SitePilot is a contract-aligned smart-worksite multi-agent application platform. The unified Supervisor routes project-scoped work to Knowledge, Database, Review, Report, OCR, Risk Investigation, Synthesis and Verifier agents. The existing HEA Graph and remediation loop remain the authoritative risk-investigation specialist.

PostgreSQL is authoritative for business state. Temporal is authoritative only for durable execution history. Conversations, model text, RAG hits, SQL plans and context snapshots are non-authoritative inputs or outputs.

## Use

All REST calls require trusted gateway headers `X-SitePilot-Tenant`, `X-SitePilot-Project` and `X-SitePilot-Actor`. Production deployments must set these after authentication; they must not be copied from untrusted client input without gateway validation.

1. Create a conversation with `POST /api/v1/conversations` when multi-turn history is needed.
2. Upload project documents through `POST /api/v1/knowledge/documents`. Supported parsers are TXT, Markdown, CSV, DOCX, PPTX, XLSX and text PDF. Images and scanned PDFs return `OCR_REQUIRED`.
3. Register a server-owned data-source credential reference with `POST /api/v1/data-sources`, then persist a schema snapshot. Models never receive or choose connection URLs or credentials.
4. Create a unified task with `POST /api/v1/platform/tasks`; inspect its validated `tasks`, parallel `levels`, budget and deadline; execute with `POST /api/v1/platform/tasks/{taskId}/execute`.
5. Download generated report artifacts from `GET /api/v1/reports/jobs/{jobId}/artifact`.

The admin console at `/` provides Contract Platform and Risk Investigation tabs. Browser namespace values are suitable for local/demo use only; production must inject trusted identity through the gateway.

## Specialist behavior and limits

- Knowledge uses local HTTP embedding when `SITEPILOT_EMBEDDING_ENDPOINT` is configured. The deterministic embedding provider is for offline tests, not a production semantic-quality claim. Retrieval is hybrid lexical/vector and returns versioned source locations and quotes. Low-evidence answers refuse.
- Database Q&A supports PostgreSQL/MySQL/Kingbase/API registrations, but this delivery enables real execution only for the configured control-plane PostgreSQL adapter. Other source types are registration/port support and return `DATA_SOURCE_ADAPTER_UNAVAILABLE` until an adapter is deployed.
- SQL execution is AST checked, single-statement SELECT-only, table/column allowlisted, project-scoped, row-limited, timeout-bounded and redacted. SQL plans are not business facts.
- Review is deterministic template/rule evaluation. Expired templates, missing references/quotes and low confidence produce `NEEDS_HUMAN_REVIEW`.
- Report values, tables and citations are code-bound; narrative is non-authoritative. DOCX is always the primary artifact. PDF uses LibreOffice and otherwise persists `DOCX_READY` with `PDF_CONVERTER_UNAVAILABLE`.
- OCR uses an HTTP adapter when `SITEPILOT_OCR_ENDPOINT` is configured. The deterministic adapter is test/demo-only. ID-card values are masked before persistence and response.
- Risk tasks are referenced through the existing `ControlPlaneService`; HEA graph and remediation state are not duplicated.

## Operations and maintenance

Use `make demo-up` / `make demo-down`. Health and metrics are at `/actuator/health` and `/actuator/prometheus`. Platform spans use names such as `sitepilot.KnowledgeService.answer`; platform counters and timers are documented in `METRICS_CATALOGUE.md`.

Before upgrading, back up PostgreSQL with `scripts/backup.sh`, retain the SHA-256 sidecar, and separately protect Temporal persistence. After restore, verify Flyway V1-V10, platform task reads, knowledge citations, SQL fail-closed behavior, report download, OCR masking, risk read models and audit chronology.

Rotate MCP HMAC, database, Grafana and workload credentials outside the repository. Never put credentials in prompts, trace attributes or evaluation artifacts. Review project/table/column allowlists whenever schemas change. Rebuild knowledge indexes after parser or embedding-model changes.

## Verification

Default offline gates are `make lint`, `make all-test` and `make evaluate`. They require Docker but not paid models, Jev, customer data or GPUs. Local-model and Jev commands are explicit opt-in commands. Jev live evaluation is currently blocked by the provider's HTTP 451 regional policy and no result is fabricated.

## Approved scope exceptions

This delivery excludes pretraining, fine-tuning, LoRA/QLoRA, PPO, training-data construction and training-data delivery. Industry adaptation is implemented with local base models, RAG, typed agents, tools, deterministic policy and evaluation. Internet crawling and production deployments of MySQL/Kingbase/API/OCR adapters are not claimed as complete in this repository.
