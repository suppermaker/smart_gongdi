package com.sitepilot.controlplane.contextassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.domain.InvestigationTaskEntity;
import com.sitepilot.controlplane.domain.ProjectEntity;
import com.sitepilot.controlplane.domain.RiskEntity;
import com.sitepilot.controlplane.domain.SourceEventEntity;
import com.sitepilot.controlplane.error.ApiException;
import com.sitepilot.controlplane.repository.InvestigationTaskRepository;
import com.sitepilot.controlplane.repository.ProjectRepository;
import com.sitepilot.controlplane.repository.RiskRepository;
import com.sitepilot.controlplane.repository.SourceEventRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class ContextAssemblyIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("sitepilot.mcp.hmac-secret",
                () -> "test-only-secret-test-only-secret-1234");
    }

    @Autowired ContextAssemblyService contexts;
    @Autowired ContextSnapshotRepository snapshots;
    @Autowired ProjectRepository projects;
    @Autowired SourceEventRepository events;
    @Autowired RiskRepository risks;
    @Autowired InvestigationTaskRepository tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private InvestigationTaskEntity task;

    @BeforeEach
    void seed() {
        jdbc.update("delete from context_snapshots");
        jdbc.update("delete from routing_decisions");
        jdbc.update("delete from evidence_edges");
        jdbc.update("delete from graph_nodes");
        jdbc.update("delete from graph_revisions");
        tasks.deleteAll();
        risks.deleteAll();
        events.deleteAll();
        projects.deleteAll();
        projects.saveAndFlush(new ProjectEntity("tenant-a", "project-a", "Alpha"));
        events.saveAndFlush(new SourceEventEntity(
                "tenant-a", "project-a", "event-1", "equipment.offline",
                OffsetDateTime.now().minusMinutes(5),
                "{\"zone\":\"zone-a\",\"offline_duration_seconds\":300}"));
        risks.saveAndFlush(new RiskEntity(
                "tenant-a", "project-a", "risk-1", "event-1", "Crane offline"));
        task = tasks.saveAndFlush(new InvestigationTaskEntity(
                "tenant-a", "project-a", "task-1", "Find the outage cause", "risk-1",
                "PENDING", 2, "{}", "hash"));
        seedRouting();
        seedGraph();
    }

    @Test
    void buildsRoleScopedContextPinsCounterevidenceAndReusesSnapshot() {
        RequestContext context = new RequestContext("tenant-a", "project-a", "actor");
        Map<String, Object> first = contexts.assemble(
                context, "task-1", "INVESTIGATOR", "route-2-network_diagnosis",
                task.getVersion(), 2, "context-policy.v1", "investigator.v2");
        Map<String, Object> second = contexts.assemble(
                context, "task-1", "INVESTIGATOR", "route-2-network_diagnosis",
                task.getVersion(), 2, "context-policy.v1", "investigator.v2");

        assertThat(first.get("applied")).isEqualTo(true);
        assertThat(second.get("applied")).isEqualTo(false);
        assertThat(first.get("contextDigest")).isEqualTo(second.get("contextDigest"));
        assertThat(snapshots.countByTenantIdAndProjectIdAndTaskId(
                "tenant-a", "project-a", "task-1")).isEqualTo(1);

        JsonNode pack = (JsonNode) first.get("contextPack");
        assertThat(pack.path("role").asText()).isEqualTo("investigator");
        assertThat(pack.path("allowed_tools").toString())
                .contains("get-gateway-status")
                .doesNotContain("merge_graph_delta");
        assertThat(pack.path("evidence_cards").toString())
                .contains("ev-support", "ev-counter")
                .doesNotContain("ev-expired");
        assertThat(pack.path("manifest").path("excluded_counts")
                .path("expired").asInt()).isEqualTo(1);
    }

    @Test
    void buildsV2FullContextWithFrontierHandlesAndSelectionReasons() {
        Map<String, Object> result = contexts.assemble(
                new RequestContext("tenant-a", "project-a", "actor"),
                "task-1", "INVESTIGATOR", "route-2-network_diagnosis",
                task.getVersion(), 2, "context-policy.v2", "investigator.v2");

        JsonNode pack = (JsonNode) result.get("contextPack");
        assertThat(pack.path("schema_version").asText()).isEqualTo("2.0");
        assertThat(pack.path("context_mode").asText()).isEqualTo("FULL");
        assertThat(pack.path("parent_context").isNull()).isTrue();
        assertThat(pack.path("context_phase").asText()).isEqualTo("EXPLORATION");
        assertThat(pack.path("reasoning_frontier").path("hypothesis_ids").size()).isEqualTo(2);
        assertThat(pack.path("evidence_handles").toString())
                .contains("ev-support", "ev-counter", "get_evidence_detail")
                .doesNotContain("ev-expired");
        assertThat(pack.path("manifest").path("selection_reasons").path("ev-counter").toString())
                .contains("COUNTEREVIDENCE");
        assertThat(pack.path("manifest").path("token_allocation").path("counterevidence").asInt())
                .isGreaterThan(0);
    }

    @Test
    void rejectsRoleMismatchStaleRevisionAndCrossTenantAccess() {
        RequestContext context = new RequestContext("tenant-a", "project-a", "actor");
        assertThatThrownBy(() -> contexts.assemble(
                context, "task-1", "COMPLIANCE", "route-2-network_diagnosis",
                task.getVersion(), 2, "context-policy.v1", "compliance.v2"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTEXT_ROLE_MISMATCH"));
        assertThatThrownBy(() -> contexts.assemble(
                context, "task-1", "INVESTIGATOR", "route-2-network_diagnosis",
                task.getVersion(), 1, "context-policy.v1", "investigator.v2"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("STALE_GRAPH_REVISION"));
        assertThatThrownBy(() -> contexts.assemble(
                new RequestContext("tenant-b", "project-a", "actor"),
                "task-1", "INVESTIGATOR", "route-2-network_diagnosis",
                task.getVersion(), 2, "context-policy.v1", "investigator.v2"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("TASK_NOT_FOUND"));
    }

    private void seedRouting() {
        String decision = "{\"schema_version\":\"1.0\",\"decision_id\":\"route-1\"}";
        String dag = "{\"tasks\":[{\"task_id\":\"route-2-network_diagnosis\","
                + "\"role\":\"investigator\","
                + "\"objective\":\"Distinguish network from local equipment failure\","
                + "\"dependencies\":[],"
                + "\"allowed_tools\":[\"query-nearby-equipment\","
                + "\"get-gateway-status\",\"get-collector-health\",\"get_evidence_detail\"],"
                + "\"verification_stage\":null}]}";
        jdbc.update("insert into routing_decisions "
                        + "(tenant_id,project_id,task_id,graph_revision,policy_version,provider,"
                        + "model_version,input_digest,decision_id,decision,compiled_dag,status,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?)",
                "tenant-a", "project-a", "task-1", 2, "routing-policy.v1",
                "deterministic", "fixture-v1", "sha256:" + "a".repeat(64),
                "route-1", decision, dag, "AUTO_ROUTE", OffsetDateTime.now());
    }

    private void seedGraph() {
        insertNode("hyp-network", "HYPOTHESIS",
                "{\"hypothesis_id\":\"hyp-network\","
                        + "\"statement\":\"Zone uplink failure\",\"status\":\"active\"}");
        insertNode("hyp-local", "HYPOTHESIS",
                "{\"hypothesis_id\":\"hyp-local\","
                        + "\"statement\":\"Local equipment failure\",\"status\":\"active\"}");
        insertEvidence("ev-support", "Nearby devices are also offline",
                OffsetDateTime.now().plusHours(1), "network");
        insertEvidence("ev-counter", "Local controller reports healthy",
                OffsetDateTime.now().plusHours(1), "equipment");
        insertEvidence("ev-expired", "Old gateway snapshot",
                OffsetDateTime.now().minusMinutes(1), "network");
        jdbc.update("insert into evidence_edges "
                        + "(tenant_id,project_id,task_id,evidence_id,hypothesis_id,relation,created_revision) "
                        + "values (?,?,?,?,?,?,?)",
                "tenant-a", "project-a", "task-1", "ev-support",
                "hyp-network", "SUPPORTS", 1);
        jdbc.update("insert into evidence_edges "
                        + "(tenant_id,project_id,task_id,evidence_id,hypothesis_id,relation,created_revision) "
                        + "values (?,?,?,?,?,?,?)",
                "tenant-a", "project-a", "task-1", "ev-counter",
                "hyp-local", "CONTRADICTS", 1);
    }

    private void insertEvidence(String id, String claim, OffsetDateTime validUntil,
                                String category) {
        String payload = "{\"evidence_id\":\"" + id + "\",\"claim\":\"" + claim
                + "\",\"category\":\"" + category
                + "\",\"source_type\":\"tool_diagnostic\","
                + "\"source_ref\":\"source-" + id + "\",\"source_version\":\"1\","
                + "\"observed_at\":\"" + OffsetDateTime.now().minusMinutes(1) + "\","
                + "\"valid_until\":\"" + validUntil + "\","
                + "\"reliability\":\"high\","
                + "\"supports\":[],\"contradicts\":[],"
                + "\"provenance\":{\"tool_call_id\":\"call-" + id + "\"}}";
        insertNode(id, "EVIDENCE", payload);
    }

    private void insertNode(String id, String type, String payload) {
        jdbc.update("insert into graph_nodes "
                        + "(tenant_id,project_id,task_id,node_id,node_type,semantic_hash,payload,"
                        + "created_revision,created_at) values (?,?,?,?,?,?,cast(? as jsonb),?,?)",
                "tenant-a", "project-a", "task-1", id, type, "hash-" + id,
                payload, 1, OffsetDateTime.now());
    }
}
