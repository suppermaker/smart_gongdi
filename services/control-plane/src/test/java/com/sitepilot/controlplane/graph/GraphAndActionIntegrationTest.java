package com.sitepilot.controlplane.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.domain.InvestigationTaskEntity;
import com.sitepilot.controlplane.domain.ProjectEntity;
import com.sitepilot.controlplane.domain.RiskEntity;
import com.sitepilot.controlplane.repository.AuditLogRepository;
import com.sitepilot.controlplane.repository.EvidenceRepository;
import com.sitepilot.controlplane.repository.InvestigationTaskRepository;
import com.sitepilot.controlplane.repository.OutboxEventRepository;
import com.sitepilot.controlplane.repository.ProjectRepository;
import com.sitepilot.controlplane.repository.RiskRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GraphAndActionIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword); r.add("sitepilot.mcp.hmac-secret", () -> "test-only-secret-test-only-secret-1234");
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired ProjectRepository projects;
    @Autowired RiskRepository risks; @Autowired InvestigationTaskRepository tasks; @Autowired EvidenceRepository evidence;
    @Autowired AuditLogRepository audits; @Autowired OutboxEventRepository outbox; @Autowired JdbcTemplate jdbc;

    @BeforeEach void seed() {
        jdbc.update("delete from evidence_edges"); jdbc.update("delete from graph_nodes");
        jdbc.update("delete from graph_revisions"); jdbc.update("delete from action_proposals");
        tasks.deleteAll(); evidence.deleteAll(); outbox.deleteAll(); audits.deleteAll(); risks.deleteAll(); projects.deleteAll();
        projects.saveAndFlush(new ProjectEntity("tenant", "project", "P"));
        risks.saveAndFlush(new RiskEntity("tenant", "project", "risk-1", "event-1", "Worker"));
        tasks.saveAndFlush(new InvestigationTaskEntity("tenant", "project", "task-1", "Investigate", "risk-1", "PENDING", 0, "{}", "hash"));
    }

    @Test void s1_merge_is_idempotent_and_gate_rejects_missing_stale_or_wrong_subject() throws Exception {
        String valid = delta("risk-1", OffsetDateTime.now().plusHours(1), "delta-1", "ev-1");
        mvc.perform(ctx(post("/api/v1/investigation-tasks/task-1/graph/deltas?expectedRevision=0")).content(valid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(ctx(post("/api/v1/investigation-tasks/task-1/graph/deltas?expectedRevision=0")).content(valid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotent").value(true));
        String gate = "{\"requiredEvidenceCategories\":[\"identity\"],\"minIndependentSources\":2,\"counterexampleSearchCompleted\":true,\"compliancePassed\":true,\"verifierApproved\":true,\"uncertaintyRepresented\":true,\"withinBudget\":true,\"modelDone\":true}";
        mvc.perform(ctx(post("/api/v1/investigation-tasks/task-1/graph/completion-gate")).content(gate))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.modelDoneIgnored").value(true));
        mvc.perform(ctx(post("/api/v1/investigation-tasks/task-1/graph/deltas?expectedRevision=1")).content(delta("wrong", OffsetDateTime.now().plusHours(1), "delta-2", "ev-2")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVIDENCE_SUBJECT_MISMATCH"));
        mvc.perform(ctx(post("/api/v1/investigation-tasks/task-1/graph/deltas?expectedRevision=1")).content(delta("risk-1", OffsetDateTime.now().minusMinutes(1), "delta-3", "ev-3")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVIDENCE_EXPIRED"));
    }

    @Test void same_node_id_with_different_meaning_has_stable_conflict_code() throws Exception {
        mvc.perform(ctx(post("/api/v1/investigation-tasks/task-1/graph/deltas?expectedRevision=0")).content(delta("risk-1", OffsetDateTime.now().plusHours(1), "delta-a", "ev-1"))).andExpect(status().isOk());
        String changed = delta("risk-1", OffsetDateTime.now().plusHours(1), "delta-b", "ev-1").replace("certificate expired", "certificate active");
        mvc.perform(ctx(post("/api/v1/investigation-tasks/task-1/graph/deltas?expectedRevision=1")).content(changed))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HYPOTHESIS_ID_CONFLICT"));
    }

    @Test void s3_l2_requires_approval_and_old_or_unchanged_observation_reopens() throws Exception {
        String proposal = "{\"schema_version\":\"1.0\",\"action_id\":\"action-1\",\"task_id\":\"task-1\",\"action_type\":\"suspend_work\",\"level\":\"L2\",\"reason\":\"verified unsafe work\",\"evidence_refs\":[\"ev-1\"],\"requires_approval\":true,\"verification_criteria\":[\"safe environment\"],\"verification_window_seconds\":0,\"idempotency_key\":\"action-key\"}";
        mvc.perform(ctx(post("/api/v1/actions")).content(proposal)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_APPROVAL"));
        mvc.perform(ctx(post("/api/v1/actions/action-1/execute?expectedVersion=0"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTION_APPROVAL_REQUIRED"));
        mvc.perform(ctx(post("/api/v1/actions/action-1/approve?expectedVersion=0"))).andExpect(status().isOk());
        mvc.perform(ctx(post("/api/v1/actions/action-1/execute?expectedVersion=1"))).andExpect(status().isOk());
        mvc.perform(ctx(post("/api/v1/actions/action-1/observe?expectedVersion=2")).content("{\"current_evidence_id\":\"old-photo\",\"environment_changed\":false,\"closure_verifier_approved\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REOPENED"));
    }

    @Test void l3_is_forbidden_and_action_replay_is_idempotent() throws Exception {
        String proposal = "{\"action_id\":\"action-x\",\"task_id\":\"task-1\",\"action_type\":\"draft\",\"level\":\"L1\",\"reason\":\"safe draft only\",\"evidence_refs\":[\"ev\"],\"verification_criteria\":[\"review\"],\"idempotency_key\":\"same\"}";
        mvc.perform(ctx(post("/api/v1/actions")).content(proposal)).andExpect(status().isOk());
        mvc.perform(ctx(post("/api/v1/actions")).content(proposal)).andExpect(status().isOk()).andExpect(jsonPath("$.actionId").value("action-x"));
        mvc.perform(ctx(post("/api/v1/actions")).content(proposal.replace("L1", "L3").replace("same", "other")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTION_LEVEL_FORBIDDEN"));
    }

    private String delta(String subject, OffsetDateTime validUntil, String deltaId, String evidenceId) throws Exception {
        JsonNode node = json.readTree("{\"schema_version\":\"2.0\",\"task_id\":\"task-1\",\"agent_task_id\":\"agent-1\",\"base_revision\":0,\"new_hypotheses\":[{\"hypothesis_id\":\"hyp-1\",\"statement\":\"certificate expired\",\"status\":\"plausible\",\"falsifiers\":[\"active certificate\"],\"supporting_evidence\":[],\"contradicting_evidence\":[]}],\"new_evidence\":[{\"claim\":\"certificate expired\",\"category\":\"identity\",\"source_type\":\"business_record\",\"source_ref\":\"registry-a\",\"source_version\":\"1\",\"observed_at\":\"2026-10-28T00:00:00Z\",\"ingested_at\":\"2026-10-28T00:00:01Z\",\"reliability\":\"authoritative\",\"completeness\":\"complete_for_query\",\"supports\":[\"hyp-1\"],\"contradicts\":[],\"provenance\":{\"tool_call_id\":\"call-1\",\"tool_name\":\"certificate_lookup\",\"agent_task_id\":\"agent-1\"}}],\"new_questions\":[],\"new_conflicts\":[],\"action_proposals\":[],\"hypothesis_updates\":[],\"usage\":{}} ");
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
        object.put("delta_id", deltaId); object.put("base_revision", deltaId.equals("delta-1") || deltaId.equals("delta-a") ? 0 : 1);
        var ev = (com.fasterxml.jackson.databind.node.ObjectNode) object.path("new_evidence").get(0);
        ev.put("evidence_id", evidenceId); ev.put("subject_ref", subject); ev.put("valid_until", validUntil.toString());
        return json.writeValueAsString(node);
    }
    private MockHttpServletRequestBuilder ctx(MockHttpServletRequestBuilder request) { return request.header("X-SitePilot-Tenant", "tenant").header("X-SitePilot-Project", "project").header("X-SitePilot-Actor", "actor").contentType(MediaType.APPLICATION_JSON); }
}
