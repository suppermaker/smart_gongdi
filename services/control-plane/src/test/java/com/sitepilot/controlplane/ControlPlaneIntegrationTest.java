package com.sitepilot.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.api.Requests.IngestEvent;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.domain.RiskEntity;
import com.sitepilot.controlplane.domain.RiskStatus;
import com.sitepilot.controlplane.repository.AuditLogRepository;
import com.sitepilot.controlplane.repository.EvidenceRepository;
import com.sitepilot.controlplane.repository.InvestigationTaskRepository;
import com.sitepilot.controlplane.repository.OutboxEventRepository;
import com.sitepilot.controlplane.repository.ProjectRepository;
import com.sitepilot.controlplane.repository.RiskRepository;
import com.sitepilot.controlplane.repository.SourceEventRepository;
import com.sitepilot.controlplane.service.ControlPlaneService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
class ControlPlaneIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("sitepilot.mcp.hmac-secret", () -> "test-only-secret-test-only-secret-1234");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ControlPlaneService service;
    @Autowired ProjectRepository projects;
    @Autowired SourceEventRepository events;
    @Autowired RiskRepository risks;
    @Autowired EvidenceRepository evidence;
    @Autowired InvestigationTaskRepository tasks;
    @Autowired OutboxEventRepository outbox;
    @Autowired AuditLogRepository audits;
    @Autowired EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanDatabase() {
        RequestContextHolder.clear();
        tasks.deleteAll();
        evidence.deleteAll();
        outbox.deleteAll();
        audits.deleteAll();
        risks.deleteAll();
        events.deleteAll();
        projects.deleteAll();
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.clear();
    }

    @Test
    void createsRiskEvidenceAndTaskAndRejectsSemanticIdConflicts() throws Exception {
        createProject("project-a");
        String riskId = ingest("project-a", "evt-1", "Crane offline");
        mvc.perform(context(post("/api/v1/events/simulated"), "project-a").content(event("evt-1", "Crane offline")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.riskId").value(riskId));
        assertThat(risks.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(audits.count()).isEqualTo(1);

        String evidenceBody = evidence(riskId, "offline");
        mvc.perform(context(post("/api/v1/evidence"), "project-a").content(evidenceBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.riskRef").value(riskId));
        mvc.perform(context(post("/api/v1/evidence"), "project-a").content(evidenceBody))
                .andExpect(status().isOk());
        mvc.perform(context(post("/api/v1/evidence"), "project-a")
                        .content(evidenceBody.replace("offline", "online")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_ID_CONFLICT"));

        String taskBody = task(riskId, "Investigate");
        mvc.perform(context(post("/api/v1/investigation-tasks"), "project-a").content(taskBody))
                .andExpect(status().isOk());
        mvc.perform(context(post("/api/v1/investigation-tasks"), "project-a")
                        .content(taskBody.replace("Investigate", "Different")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ID_CONFLICT"));
    }

    @Test
    void requiresProjectAndRiskReferences() throws Exception {
        mvc.perform(context(post("/api/v1/events/simulated"), "missing").content(event("evt", "Risk")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        createProject("project-a");
        mvc.perform(context(post("/api/v1/evidence"), "project-a").content(evidence("missing", "claim")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RISK_NOT_FOUND"));
        mvc.perform(context(post("/api/v1/investigation-tasks"), "project-a").content(task("missing", "goal")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RISK_NOT_FOUND"));
    }

    @Test
    void failsClosedForMissingHeadersAndDoesNotLeakContextBetweenRequests() throws Exception {
        createProject("project-a");
        mvc.perform(get("/api/v1/projects/current"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDENTITY_CONTEXT_REQUIRED"));
        assertThatThrownBy(RequestContextHolder::get).isInstanceOf(IllegalStateException.class);
        mvc.perform(context(get("/api/v1/projects/current"), "project-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.projectId").doesNotExist());
        assertThatThrownBy(RequestContextHolder::get).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesRequestsAndRejectsIllegalTransition() throws Exception {
        createProject("project-a");
        mvc.perform(context(post("/api/v1/events/simulated"), "project-a").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        String riskId = ingest("project-a", "evt-illegal", "Risk");
        mvc.perform(context(post("/api/v1/risks/" + riskId + "/transitions"), "project-a")
                        .content("{\"status\":\"CLOSED\",\"idempotencyKey\":\"close-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RISK_TRANSITION_NOT_ALLOWED"));
    }

    @Test
    void transitionRetriesAreIdempotentAndKeyReuseConflicts() throws Exception {
        createProject("project-transition");
        String riskId = ingest("project-transition", "evt-transition", "Risk");
        String request = "{\"status\":\"INVESTIGATING\",\"idempotencyKey\":\"transition-1\"}";
        mvc.perform(context(post("/api/v1/risks/" + riskId + "/transitions"), "project-transition").content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVESTIGATING"));
        mvc.perform(context(post("/api/v1/risks/" + riskId + "/transitions"), "project-transition").content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVESTIGATING"));
        assertThat(audits.countByTenantIdAndProjectId("tenant-a", "project-transition")).isEqualTo(2);
        assertThat(outbox.countByTenantIdAndProjectId("tenant-a", "project-transition")).isEqualTo(2);
        mvc.perform(context(post("/api/v1/risks/" + riskId + "/transitions"), "project-transition")
                        .content("{\"status\":\"AWAITING_HUMAN\",\"idempotencyKey\":\"transition-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(audits.countByTenantIdAndProjectId("tenant-a", "project-transition")).isEqualTo(2);
        assertThat(outbox.countByTenantIdAndProjectId("tenant-a", "project-transition")).isEqualTo(2);
    }

    @Test
    void concurrentSameTransitionCreatesOneSideEffect() throws Exception {
        createProject("project-transition-race");
        String riskId = ingest("project-transition-race", "evt-transition-race", "Risk");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<String>> calls = List.of(
                () -> transitionDirect(start, riskId), () -> transitionDirect(start, riskId));
        List<Future<String>> futures = calls.stream().map(executor::submit).toList();
        start.countDown();
        assertThat(futures.get(0).get()).isEqualTo(futures.get(1).get());
        executor.shutdownNow();
        assertThat(audits.countByTenantIdAndProjectId("tenant-a", "project-transition-race")).isEqualTo(2);
        assertThat(outbox.countByTenantIdAndProjectId("tenant-a", "project-transition-race")).isEqualTo(2);
        assertThat(risks.findByTenantIdAndProjectIdAndRiskId("tenant-a", "project-transition-race", riskId).orElseThrow().getStatus())
                .isEqualTo(RiskStatus.INVESTIGATING);
    }

    private String transitionDirect(CountDownLatch start, String riskId) throws Exception {
        start.await();
        RequestContextHolder.set(new RequestContext("tenant-a", "project-transition-race", "tester"));
        try {
            return service.transition(riskId, new com.sitepilot.controlplane.api.Requests.TransitionRisk(
                    "INVESTIGATING", "race-transition")).getStatus().name();
        } finally {
            RequestContextHolder.clear();
        }
    }

    @Test
    void isolatesProjects() throws Exception {
        createProject("project-a");
        String riskId = ingest("project-a", "evt-isolated", "Risk");
        mvc.perform(context(get("/api/v1/risks/" + riskId), "project-b"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RISK_NOT_FOUND"));
    }

    @Test
    void concurrentDuplicateEventReturnsOneRiskAndOneOutbox() throws Exception {
        createProject("project-race");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(() -> {
                start.await();
                RequestContextHolder.set(new RequestContext("tenant-a", "project-race", "tester"));
                try {
                    return service.ingest(new IngestEvent(
                            "evt-race", "simulated", "Race", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                            json.createObjectNode())).getRiskId();
                } finally {
                    RequestContextHolder.clear();
                }
            });
        }
        List<Future<String>> futures = calls.stream().map(executor::submit).toList();
        start.countDown();
        assertThat(futures.get(0).get()).isEqualTo(futures.get(1).get());
        executor.shutdownNow();
        assertThat(risks.countByTenantIdAndProjectId("tenant-a", "project-race")).isEqualTo(1);
        assertThat(events.countByTenantIdAndProjectId("tenant-a", "project-race")).isEqualTo(1);
        assertThat(outbox.countByTenantIdAndProjectId("tenant-a", "project-race")).isEqualTo(1);
        assertThat(audits.countByTenantIdAndProjectId("tenant-a", "project-race")).isEqualTo(1);
    }

    @Test
    void failedIngestionRollsBackEventRiskAuditAndOutbox() throws Exception {
        createProject("project-rollback");
        assertThatThrownBy(() -> mvc.perform(context(post("/api/v1/events/simulated"), "project-rollback")
                        .content(event("x".repeat(161), "Risk"))))
                .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(events.count()).isZero();
        assertThat(risks.count()).isZero();
        assertThat(audits.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void optimisticLockRejectsStaleRiskUpdate() {
        RiskEntity seed = risks.saveAndFlush(
                new RiskEntity("tenant-lock", "project-lock", "risk-lock", "event-lock", "Lock"));
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        RiskEntity firstCopy = first.find(RiskEntity.class, seed.getId());
        RiskEntity secondCopy = second.find(RiskEntity.class, seed.getId());
        first.getTransaction().begin();
        firstCopy.transition(RiskStatus.INVESTIGATING);
        first.getTransaction().commit();
        second.getTransaction().begin();
        secondCopy.transition(RiskStatus.INVESTIGATING);
        assertThatThrownBy(() -> {
            second.flush();
            second.getTransaction().commit();
        }).isInstanceOfAny(OptimisticLockException.class, ObjectOptimisticLockingFailureException.class);
        if (second.getTransaction().isActive()) second.getTransaction().rollback();
        first.close();
        second.close();
    }

    private void createProject(String project) throws Exception {
        mvc.perform(context(post("/api/v1/projects"), project).content("{\"name\":\"Alpha\"}"))
                .andExpect(status().isCreated());
    }

    private String ingest(String project, String eventId, String title) throws Exception {
        String response = mvc.perform(context(post("/api/v1/events/simulated"), project)
                        .content(event(eventId, title)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("riskId").asText();
    }

    private MockHttpServletRequestBuilder context(MockHttpServletRequestBuilder request, String project) {
        return request.header("X-SitePilot-Tenant", "tenant-a")
                .header("X-SitePilot-Project", project)
                .header("X-SitePilot-Actor", "tester")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String event(String eventId, String title) throws Exception {
        JsonNode body = json.readTree("{\"eventType\":\"simulated\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"attributes\":{}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("eventId", eventId).put("title", title);
        return json.writeValueAsString(body);
    }

    private String evidence(String riskId, String claim) throws Exception {
        JsonNode body = json.readTree("{\"evidenceId\":\"ev-1\",\"sourceType\":\"sensor\",\"sourceRef\":\"crane-1\",\"sourceVersion\":\"7\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"digest\":\"sha256:x\",\"structuredValue\":{\"online\":false},\"reliability\":\"HIGH\",\"completeness\":\"COMPLETE\",\"toolProvenance\":{\"toolCallId\":\"call-1\"}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("riskRef", riskId).put("claim", claim);
        return json.writeValueAsString(body);
    }

    private String task(String riskId, String goal) throws Exception {
        JsonNode body = json.readTree("{\"taskId\":\"task-1\",\"status\":\"PENDING\",\"graphRevision\":0,\"budgets\":{\"maxToolCalls\":5}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("riskRef", riskId).put("goal", goal);
        return json.writeValueAsString(body);
    }
}
