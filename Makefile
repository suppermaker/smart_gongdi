SHELL := /bin/bash

SCENARIO_GYM := services/scenario-gym
CONTROL_PLANE := services/control-plane
AGENT_RUNTIME := services/agent-runtime
TEMPORAL_COMPOSE := infra/compose/compose.temporal.yml
M3_COMPOSE := infra/compose/compose.m3.yml
M3_SECRET := test-only-m3-secret-test-only-m3-secret

.PHONY: help bootstrap contract-test scenario-test java-contract-test m0-test m1-test m2-test m3-test m4-test m5-test m6-test m7-test m8-test m10-test m11-test evaluate live-model-evaluate jev-live-evaluate jev-smoke-test demo-up demo-down all-test lint

help:
	@echo "SitePilot development commands:"
	@echo "  make bootstrap          Install Python dependencies"
	@echo "  make m0-test            Run the complete M0 verification suite"
	@echo "  make m1-test            Run the control-plane verification suite"
	@echo "  make m2-test            Run WorkflowEnvironment and real Temporal tests"
	@echo "  make m3-test            Run Java and Python real MCP integration"
	@echo "  make m4-test            Run graph merge/gate PostgreSQL tests"
	@echo "  make m5-test            Run dynamic multi-agent DAG tests"
	@echo "  make m6-test            Run action/closure and Temporal replay tests"
	@echo "  make m7-test            Run deterministic Scenario Gym evaluation tests"
	@echo "  make m8-test            Run observability/read API/console tests"
	@echo "  make m10-test           Run revision-scoped context assembly tests"
	@echo "  make evaluate           Generate reproducible M7 artifacts"
	@echo "  make m11-test           Run contract-aligned platform multi-agent tests"
	@echo "  make demo-up/down       Start or stop the complete demo"
	@echo "  make all-test           Run M0 through M8 gates"
	@echo "  make lint               Lint all Python code"

bootstrap:
	cd $(SCENARIO_GYM) && uv sync --dev
	cd $(AGENT_RUNTIME) && uv sync --dev

contract-test:
	cd $(SCENARIO_GYM) && uv run python validate_m0.py contracts

scenario-test:
	cd $(SCENARIO_GYM) && uv run python validate_m0.py scenarios
	cd $(SCENARIO_GYM) && uv run pytest -q

java-contract-test:
	cd $(CONTROL_PLANE) && mvn test -q

m0-test: contract-test scenario-test java-contract-test

m1-test:
	cd $(CONTROL_PLANE) && mvn test

m2-test:
	@set -euo pipefail; \
	export TEMPORAL_DB_PASSWORD=test-only-temporal-db; \
	docker compose -f $(TEMPORAL_COMPOSE) up -d --wait; \
	trap 'cd "$(CURDIR)" && docker compose -f $(TEMPORAL_COMPOSE) down -v' EXIT; \
	(cd $(AGENT_RUNTIME) && TEMPORAL_ADDRESS=localhost:17233 uv run pytest -q -m 'not m3_integration')

m3-test:
	@set -euo pipefail; \
	export SITEPILOT_DB_PASSWORD=test-only-sitepilot-db TEMPORAL_DB_PASSWORD=test-only-temporal-db; \
	docker compose -f $(M3_COMPOSE) up -d --wait; \
	docker compose -f $(TEMPORAL_COMPOSE) up -d --wait; \
	trap 'kill $$java_pid 2>/dev/null || true; wait $$java_pid 2>/dev/null || true; cd "$(CURDIR)"; docker compose -f $(M3_COMPOSE) down -v; docker compose -f $(TEMPORAL_COMPOSE) down -v' EXIT; \
	cd $(CONTROL_PLANE); \
	DATABASE_URL=jdbc:postgresql://localhost:15433/sitepilot DATABASE_USER=sitepilot DATABASE_PASSWORD=$$SITEPILOT_DB_PASSWORD PORT=18080 SITEPILOT_MCP_HMAC_SECRET=$(M3_SECRET) SITEPILOT_MCP_ALLOWED_HOSTS=localhost,127.0.0.1 mvn spring-boot:run > /tmp/sitepilot-m3-java.log 2>&1 & java_pid=$$!; \
	cd "$(CURDIR)"; \
	for i in $$(seq 1 60); do curl -fsS -H 'X-SitePilot-Tenant: tenant-m3' -H 'X-SitePilot-Project: project-m3' -H 'X-SitePilot-Actor: setup' http://localhost:18080/api/v1/projects/current >/dev/null 2>&1 && break; kill -0 $$java_pid || { cat /tmp/sitepilot-m3-java.log; exit 1; }; sleep 1; done; \
	curl -fsS -X POST -H 'Content-Type: application/json' -H 'X-SitePilot-Tenant: tenant-m3' -H 'X-SitePilot-Project: project-m3' -H 'X-SitePilot-Actor: setup' -d '{"name":"M3"}' http://localhost:18080/api/v1/projects >/dev/null; \
	risk=$$(curl -fsS -X POST -H 'Content-Type: application/json' -H 'X-SitePilot-Tenant: tenant-m3' -H 'X-SitePilot-Project: project-m3' -H 'X-SitePilot-Actor: setup' -d '{"eventId":"event-m3","eventType":"simulated","title":"M3 risk","occurredAt":"2026-01-01T00:00:00Z","attributes":{}}' http://localhost:18080/api/v1/events/simulated | python3 -c 'import json,sys; print(json.load(sys.stdin)["riskId"])'); \
	curl -fsS -X POST -H 'Content-Type: application/json' -H 'X-SitePilot-Tenant: tenant-m3' -H 'X-SitePilot-Project: project-m3' -H 'X-SitePilot-Actor: setup' -d "{\"taskId\":\"task-m3\",\"goal\":\"M3\",\"riskRef\":\"$$risk\",\"status\":\"PENDING\",\"graphRevision\":0,\"budgets\":{}}" http://localhost:18080/api/v1/investigation-tasks >/dev/null; \
	token=$$(python3 scripts/issue_m3_token.py $(M3_SECRET) tenant-m3 project-m3 actor-m3 task-m3 INVESTIGATOR get_investigation_task,graph_read); \
	cd $(CONTROL_PLANE) && mvn test -q; \
	cd "$(CURDIR)/$(AGENT_RUNTIME)" && M3_CONTROL_PLANE_URL=http://localhost:18080 M3_MCP_TOKEN=$$token TEMPORAL_ADDRESS=localhost:17233 uv run pytest -q -m m3_integration

m4-test:
	cd $(CONTROL_PLANE) && mvn -q -Dtest=GraphAndActionIntegrationTest test

m5-test:
	cd $(AGENT_RUNTIME) && uv run pytest -q tests/test_m5_agent_protocol.py

m6-test:
	cd $(CONTROL_PLANE) && mvn -q -Dtest=GraphAndActionIntegrationTest,ReportGenerationPortTest test
	cd $(AGENT_RUNTIME) && uv run pytest -q tests/test_m6_remediation.py

m7-test:
	cd $(SCENARIO_GYM) && uv run pytest -q tests/test_m7_evaluation.py

m8-test:
	cd $(CONTROL_PLANE) && mvn -q -Dtest=TraceChainTest,AdminConsoleContractTest test
	cd $(AGENT_RUNTIME) && uv run pytest -q tests/test_m8_telemetry.py
	SITEPILOT_DB_PASSWORD=test-only-sitepilot-db TEMPORAL_DB_PASSWORD=test-only-temporal-db SITEPILOT_MCP_HMAC_SECRET=test-only-m8-secret-test-only-m8-secret GRAFANA_ADMIN_PASSWORD=test-only-grafana docker compose -f infra/compose/compose.demo.yml config -q

evaluate:
	cd $(SCENARIO_GYM) && uv run python evaluate.py

demo-up:
	bash scripts/demo-up.sh

demo-down:
	bash scripts/demo-down.sh

m10-test:
	cd $(CONTROL_PLANE) && mvn -q -Dtest=ContextAssemblyIntegrationTest,McpIntegrationTest,ContractFixtureTest test
	cd $(AGENT_RUNTIME) && uv run pytest -q tests/test_context_contracts.py tests/test_context_client.py tests/test_routing_activities.py
	cd $(SCENARIO_GYM) && uv run pytest -q tests/test_m10_context_evaluation.py

m11-test:
	cd $(CONTROL_PLANE) && mvn -q -Dtest=PlatformTaskIntegrationTest,ContractModulesEndToEndTest,McpIntegrationTest,AdminConsoleContractTest test
	cd $(AGENT_RUNTIME) && uv run pytest -q tests/test_platform_agents.py tests/test_platform_tools.py

all-test: m0-test m1-test m2-test m3-test m4-test m5-test m6-test m7-test m8-test m10-test m11-test

lint:
	cd $(SCENARIO_GYM) && uv run ruff check validate_m0.py loader.py evaluate.py context_eval.py live_model_eval.py jev_live_eval.py tests
	cd $(AGENT_RUNTIME) && uv run ruff check src tests
	python3 scripts/check_java_style.py
	cd $(CONTROL_PLANE) && mvn -q -DskipTests compile

jev-smoke-test:
	cd services/agent-runtime && PYTHONPATH=src uv run python ../../scripts/jev_smoke.py

live-model-evaluate:
	cd $(SCENARIO_GYM) && uv run python live_model_eval.py --base-url $${SITEPILOT_LLM_BASE_URL:-http://127.0.0.1:18000} --model $${SITEPILOT_LLM_MODEL:-smart-worksite-chat} --concurrency $${SITEPILOT_LLM_EVAL_CONCURRENCY:-1}

jev-live-evaluate:
	cd $(SCENARIO_GYM) && PYTHONPATH=../agent-runtime/src uv run python jev_live_eval.py --base-url $${JEV_BASE_URL:-https://api.typesafe.ai} --model "$${JEV_MODEL:-}" --concurrency $${JEV_EVAL_CONCURRENCY:-2}
