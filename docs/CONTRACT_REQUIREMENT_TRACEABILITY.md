# Contract Requirement Traceability

Status values: `DONE`, `PARTIAL_DEPLOYMENT`, `NOT_IN_APPROVED_GOAL`, `OUT_OF_SCOPE_BY_AGREEMENT`, `BLOCKED_EXTERNAL`.

| Approved capability | Status | Implementation evidence | Verification evidence / limitation |
|---|---|---|---|
| Unified task/conversation API and Supervisor routing | DONE | `PlatformTaskController`, `ConversationController`, `PlatformDagCompiler`; PostgreSQL V8/V10 | `PlatformTaskIntegrationTest`, `ContractModulesEndToEndTest`; DAG persists tasks, levels, tool allowlists, budgets and deadline |
| Project RAG and citations | DONE | `KnowledgeService`, V9/V10; TXT/MD/CSV/DOCX/PPTX/XLSX/PDF plus image/scanned fallback | Contract E2E upload/search/answer/reindex/delete/isolation; deterministic embeddings are test-only, production uses configured local HTTP embedding |
| Safe database Q&A | DONE | `DatabaseQaService`; typed provider, JSqlParser, table/column/project/limit/timeout/redaction guard | Contract E2E uses a real PostgreSQL container and rejects DML, multiple statements, forbidden tables/columns and cross-project reads; non-PostgreSQL adapters are `PARTIAL_DEPLOYMENT` |
| Compliance review | DONE | V10 review templates/jobs and deterministic Finding JSON | Contract E2E verifies severity/reference/location; expired/missing-reference/low-confidence results return `NEEDS_HUMAN_REVIEW` |
| DOCX/PDF report generation | DONE | V10 report templates/jobs, Apache POI, LibreOffice adapter, persistent status/idempotency | Contract E2E validates DOCX ZIP signature; PDF returns artifact or `PDF_CONVERTER_UNAVAILABLE` with DOCX retained |
| OCR/custom extraction | DONE | V10 OCR jobs, HTTP OCR adapter config, deterministic test adapter, masking/audit | Contract E2E verifies field confidence/location/source reference, ID masking and project isolation; production OCR engine deployment is `PARTIAL_DEPLOYMENT` |
| Existing risk investigation bridge | DONE | `PlatformExecutionService` calls existing `ControlPlaneService` task port | Platform intent tests plus existing graph/action/closure tests; no duplicate risk lifecycle |
| MCP, permissions and prompt-injection safety | DONE | `/mcp`, HMAC identity, platform and risk tool schemas, role+permission checks, audit | `McpIntegrationTest`; model schemas exclude identity, credentials, URL, storage and permission controls |
| Trace, Prometheus and admin console | DONE | `TraceChainAspect`, platform counters/timers, dual-view static console | `TraceChainTest`, Contract E2E metric assertions, `AdminConsoleContractTest`; responsive CSS contract at 960/560px, no pixel-perfect device certification |
| Architecture/development/use/maintenance/run/security/backup/test docs | DONE | `ARCHITECTURE.md`, `DEVELOPMENT.md`, `CONTRACT_PLATFORM_DELIVERY.md`, `RUNBOOK.md`, `SECURITY.md`, `BACKUP_RESTORE.md`, `EVALUATION.md` | Documentation distinguishes ports/test adapters from production deployments |
| Default deterministic validation | DONE | Make targets and offline providers | `make lint`, `make all-test`, `make evaluate`; final run evidence recorded in the completion report |
| Internet policy crawler | NOT_IN_APPROVED_GOAL | No crawler is claimed | Documents can be uploaded or ingested through controlled application APIs |
| Model training/fine-tuning/training data | OUT_OF_SCOPE_BY_AGREEMENT | ADR-012 and delivery guide | No pretraining, LoRA/QLoRA, PPO, fine-tuning or training-data pipeline |
| Jev live evaluation | BLOCKED_EXTERNAL | Optional provider only | Provider returns HTTP 451 regional restriction; blocker artifact retained, no metrics fabricated |
