# M4 Hypothesis-Evidence Graph Runtime

Flyway V2 adds immutable revisions, typed graph nodes and evidence edges. `GraphMergeService` is the only graph writer, locks the task revision, validates references, evidence subject/freshness/provenance and stable semantic IDs. The deterministic completion gate ignores model `done` and requires evidence categories, independent sources, no critical conflict, counterexample search, freshness, compliance, verifier, uncertainty and budget.

S1 integration tests use PostgreSQL Testcontainers and include missing evidence, wrong subject, expired evidence and semantic ID conflict attacks.
