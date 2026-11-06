# M1 Control Plane

M1 implements the Java 17 / Spring Boot 3 authoritative control-plane vertical slice.

## Boundary

Trusted namespace and actor context comes only from `X-SitePilot-Tenant`, `X-SitePilot-Project`, and `X-SitePilot-Actor`. Request bodies do not accept identity fields. The request filter always clears its thread-local context in `finally` and missing headers fail closed.

PostgreSQL is authoritative and Flyway owns schema creation. Integration tests use Testcontainers 2.0.5 with PostgreSQL 16; Docker is mandatory and unavailable Docker fails the suite rather than skipping tests.

## Implemented

- Project, source event, risk, evidence, investigation task, audit log, and transactional outbox persistence.
- Project existence checks before event ingestion and risk-reference validation for evidence/tasks.
- Idempotent event ingestion, including concurrent duplicate recovery after a database uniqueness conflict.
- Evidence/task semantic hashes: an existing ID with changed meaning returns `EVIDENCE_ID_CONFLICT` or `TASK_ID_CONFLICT`.
- Fail-closed risk state machine, Bean Validation, stable errors, optimistic locking, and fully idempotent transitions. Replaying the same key/state returns the current risk without side effects; reusing a key for another state returns `IDEMPOTENCY_KEY_CONFLICT`.
- Transition concurrency is serialized with a PostgreSQL row lock so the same request produces at most one state change, audit record, and outbox event.
- REST responses do not expose trusted tenant/project namespace fields; project endpoints use an explicit response DTO.
- Transaction rollback coverage proves source event, risk, audit, and outbox writes remain atomic.

## Verification

`mvn test` runs 13 Java tests with 0 failures, 0 errors, and 0 skipped. The suite connects to Docker 29 (API 1.52), starts PostgreSQL 16.15, validates migration V1, and applies it to an empty schema. Root `make m0-test`, `make lint`, and `make all-test` are the repository gates.
