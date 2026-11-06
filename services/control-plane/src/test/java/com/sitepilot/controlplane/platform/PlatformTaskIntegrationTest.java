package com.sitepilot.controlplane.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sitepilot.controlplane.domain.ProjectEntity;
import com.sitepilot.controlplane.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class PlatformTaskIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("sitepilot.mcp.hmac-secret", () -> "test-only-secret-test-only-secret-1234");
    }

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;

    @BeforeEach
    void seed() {
        projects.deleteAll();
        projects.saveAndFlush(new ProjectEntity("tenant-platform", "project-platform", "Platform"));
    }

    @Test
    void routesContractModulesAndIsolatesProjects() throws Exception {
        mvc.perform(ctx(post("/api/v1/platform/tasks")).content(body(
                        "task-hybrid", "根据规范分析本月高风险隐患数量", "answer", "idem-hybrid")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("HYBRID_QA"))
                .andExpect(jsonPath("$.plan.orchestration").value("DYNAMIC_MULTI_AGENT_DAG"))
                .andExpect(jsonPath("$.plan.status").value("VALIDATED"))
                .andExpect(jsonPath("$.plan.levels[0].length()").value(2))
                .andExpect(jsonPath("$.plan.tasks[0].allowedTools").isArray())
                .andExpect(jsonPath("$.plan.deadlineSeconds").value(120));
        mvc.perform(ctx(get("/api/v1/platform/tasks/task-hybrid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"));
        mvc.perform(get("/api/v1/platform/tasks/task-hybrid")
                        .header("X-SitePilot-Tenant", "tenant-platform")
                        .header("X-SitePilot-Project", "other-project")
                        .header("X-SitePilot-Actor", "tester")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void routesReportReviewOcrAndRiskTasks() throws Exception {
        assertIntent("task-report", "生成项目风险月报", "report", "REPORT_GENERATION");
        assertIntent("task-review", "审查施工方案", "review", "DOCUMENT_REVIEW");
        assertIntent("task-ocr", "识别合同甲乙方和金额", "ocr", "OCR_EXTRACTION");
        assertIntent("task-risk", "调查塔吊离线根因", "answer", "RISK_INVESTIGATION");
    }

    private void assertIntent(String id, String input, String output, String intent) throws Exception {
        mvc.perform(ctx(post("/api/v1/platform/tasks")).content(body(id, input, output, "idem-" + id)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.intent").value(intent));
    }

    private String body(String id, String input, String output, String idem) {
        return "{\"taskId\":\"" + id + "\",\"userInput\":\"" + input
                + "\",\"requestedOutput\":\"" + output
                + "\",\"attachmentRefs\":[],\"idempotencyKey\":\"" + idem + "\"}";
    }

    private MockHttpServletRequestBuilder ctx(MockHttpServletRequestBuilder request) {
        return request.header("X-SitePilot-Tenant", "tenant-platform")
                .header("X-SitePilot-Project", "project-platform")
                .header("X-SitePilot-Actor", "tester")
                .contentType(MediaType.APPLICATION_JSON);
    }
}
