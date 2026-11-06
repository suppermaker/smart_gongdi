package com.sitepilot.controlplane.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.contracts.GraphDelta;
import com.sitepilot.controlplane.action.ActionService;
import com.sitepilot.controlplane.contextassembly.ContextAssemblyService;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.graph.GraphMergeService;
import com.sitepilot.controlplane.domain.EvidenceEntity;
import com.sitepilot.controlplane.domain.InvestigationTaskEntity;
import com.sitepilot.controlplane.domain.ProjectEntity;
import com.sitepilot.controlplane.domain.RiskEntity;
import com.sitepilot.controlplane.error.ApiException;
import com.sitepilot.controlplane.repository.EvidenceRepository;
import com.sitepilot.controlplane.repository.InvestigationTaskRepository;
import com.sitepilot.controlplane.repository.ProjectRepository;
import com.sitepilot.controlplane.repository.RiskRepository;
import com.sitepilot.controlplane.repository.SourceEventRepository;
import com.sitepilot.controlplane.routing.RoutingDecisionService;
import com.sitepilot.controlplane.platform.PlatformContracts;
import com.sitepilot.controlplane.platform.PlatformExecutionService;
import com.sitepilot.controlplane.platform.PlatformTaskService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpToolService {
    private final ProjectRepository projects;
    private final RiskRepository risks;
    private final SourceEventRepository events;
    private final InvestigationTaskRepository tasks;
    private final EvidenceRepository evidence;
    private final GraphMergeService graphs;
    private final ActionService actions;
    private final RoutingDecisionService routing;
    private final ContextAssemblyService contexts;
    private final PlatformTaskService platformTasks;
    private final PlatformExecutionService platformExecution;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public McpToolService(ProjectRepository projects, RiskRepository risks,
                          SourceEventRepository events, InvestigationTaskRepository tasks,
                          EvidenceRepository evidence, GraphMergeService graphs,
                          ActionService actions, RoutingDecisionService routing,
                          ContextAssemblyService contexts, PlatformTaskService platformTasks,
                          PlatformExecutionService platformExecution, ObjectMapper json,
                          JdbcTemplate jdbc) {
        this.projects = projects;
        this.risks = risks;
        this.events = events;
        this.tasks = tasks;
        this.evidence = evidence;
        this.graphs = graphs;
        this.actions = actions;
        this.routing = routing;
        this.contexts = contexts;
        this.platformTasks = platformTasks;
        this.platformExecution = platformExecution;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> projectContext(McpPrincipal p, long expectedVersion, int expectedRevision) {
        InvestigationTaskEntity task = checkedTask(p, expectedVersion, expectedRevision);
        ProjectEntity project = projects.findByTenantIdAndProjectId(p.tenantId(), p.projectId())
                .orElseThrow(() -> notFound("PROJECT_NOT_FOUND", "project not found"));
        RiskEntity risk = checkedRisk(p, task.getRiskRef(), task);
        return Map.of("project", Map.of("name", project.getName(), "version", project.getVersion()),
                "task", taskMap(task), "risk", riskMap(risk));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> risk(McpPrincipal p, String riskId, long expectedVersion, int expectedRevision) {
        InvestigationTaskEntity task = checkedTask(p, expectedVersion, expectedRevision);
        return riskMap(checkedRisk(p, riskId, task));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> task(McpPrincipal p, long expectedVersion, int expectedRevision) {
        return taskMap(checkedTask(p, expectedVersion, expectedRevision));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> evidence(McpPrincipal p, String riskId, long expectedVersion, int expectedRevision) {
        InvestigationTaskEntity task = checkedTask(p, expectedVersion, expectedRevision);
        checkedRisk(p, riskId, task);
        List<Map<String, Object>> items = evidence.findAllByTenantIdAndProjectIdAndRiskRef(
                        p.tenantId(), p.projectId(), riskId).stream()
                .map(item -> Map.<String, Object>of("evidenceId", item.getEvidenceId(),
                        "riskRef", item.getRiskRef(), "claim", item.getClaim())).toList();
        return Map.of("riskId", riskId, "items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> evidenceDetail(McpPrincipal p, String evidenceId,
                                              long expectedVersion, int expectedRevision) {
        checkedTask(p, expectedVersion, expectedRevision);
        List<String> rows = jdbc.queryForList(
                "select payload::text from graph_nodes where tenant_id=? and project_id=? "
                        + "and task_id=? and node_id=? and node_type='EVIDENCE' "
                        + "and created_revision<=? order by created_revision desc limit 1",
                String.class, p.tenantId(), p.projectId(), p.taskId(), evidenceId, expectedRevision);
        if (rows.isEmpty()) throw notFound("EVIDENCE_NOT_FOUND", "evidence not found");
        JsonNode payload = readJson(rows.get(0));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidenceId", evidenceId);
        result.put("graphRevision", expectedRevision);
        result.put("claim", payload.path("claim").asText("Structured evidence"));
        result.put("sourceType", payload.path("source_type").asText("unknown"));
        result.put("sourceRef", payload.path("source_ref").asText("unknown"));
        result.put("sourceVersion", payload.path("source_version").asText("unknown"));
        result.put("observedAt", payload.path("observed_at").asText());
        result.put("validUntil", payload.path("valid_until").isMissingNode()
                ? "" : payload.path("valid_until").asText());
        result.put("structuredValue", payload.path("structured_value"));
        result.put("provenance", payload.path("provenance"));
        result.put("sourceDigest", "sha256:" + digest(rows.get(0)));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> routingContext(McpPrincipal p, long expectedVersion, int expectedRevision) {
        InvestigationTaskEntity task = checkedTask(p, expectedVersion, expectedRevision);
        RiskEntity risk = checkedRisk(p, task.getRiskRef(), task);
        var event = events.findByTenantIdAndProjectIdAndEventId(
                        p.tenantId(), p.projectId(), risk.getSourceEventId())
                .orElseThrow(() -> notFound("SOURCE_EVENT_NOT_FOUND", "source event not found"));
        Map<String, Object> eventSummary = Map.of(
                "eventId", event.getEventId(), "eventType", event.getEventType(),
                "occurredAt", event.getOccurredAt().toString(),
                "attributes", readJson(event.getAttributes()));
        return Map.of(
                "event", eventSummary,
                "risk", riskMap(risk),
                "task", taskMap(task),
                "graph", graphs.read(context(p), p.taskId()),
                "factRefs", List.of("event:eventType", "event:attributes", "risk:status"));
    }

    public Map<String, Object> recordRouting(McpPrincipal p, Map<String, Object> decision,
                                             Map<String, Object> compiledDag,
                                             long expectedVersion, int expectedRevision) {
        InvestigationTaskEntity task = checkedTask(p, expectedVersion, expectedRevision);
        return routing.record(context(p), task.getTaskId(), expectedRevision,
                json.valueToTree(decision), json.valueToTree(compiledDag));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRouting(McpPrincipal p, int graphRevision, String policyVersion,
                                          long expectedVersion, int expectedRevision) {
        InvestigationTaskEntity task = checkedTask(p, expectedVersion, expectedRevision);
        return routing.get(context(p), task.getTaskId(), graphRevision, policyVersion);
    }

    public Map<String, Object> agentContext(McpPrincipal p, String agentTaskId,
                                            String contextPolicyVersion, String promptVersion,
                                            long expectedVersion, int expectedRevision) {
        checkedTask(p, expectedVersion, expectedRevision);
        return contexts.assemble(context(p), p.taskId(), p.role(), agentTaskId,
                expectedVersion, expectedRevision, contextPolicyVersion, promptVersion);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> proposeQuestion(McpPrincipal p, String question, String rationale,
                                                long expectedVersion, int expectedRevision) {
        InvestigationTaskEntity task = checkedTask(p, expectedVersion, expectedRevision);
        return Map.of("proposalType", "HUMAN_QUESTION", "status", "DRAFT", "taskId", task.getTaskId(),
                "question", question, "rationale", rationale, "authoritativeStateChanged", false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> graph(McpPrincipal p, long expectedVersion, int expectedRevision) {
        checkedTask(p, expectedVersion, expectedRevision);
        return graphs.read(context(p), p.taskId());
    }

    public Map<String, Object> mergeGraph(McpPrincipal p, Map<String, Object> delta,
                                           long expectedVersion, int expectedRevision) {
        checkedTask(p, expectedVersion, expectedRevision);
        return graphs.merge(context(p), p.taskId(), expectedRevision, json.convertValue(delta, GraphDelta.class));
    }

    public Map<String, Object> proposeAction(McpPrincipal p, Map<String, Object> proposal,
                                              long expectedVersion, int expectedRevision) {
        checkedTask(p, expectedVersion, expectedRevision);
        JsonNode value = json.valueToTree(proposal);
        if (!p.taskId().equals(value.path("task_id").asText())) {
            throw new ApiException("ACTION_TASK_MISMATCH", 409, "action is not for authenticated task");
        }
        return actions.propose(context(p), value);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> readAction(McpPrincipal p, String actionId,
                                           long expectedVersion, int expectedRevision) {
        checkedTask(p, expectedVersion, expectedRevision);
        Map<String, Object> result = actions.read(context(p), actionId);
        if (!p.taskId().equals(result.get("taskId"))) {
            throw new ApiException("ACTION_NOT_FOUND", 404, "action not found");
        }
        return result;
    }

    public Map<String, Object> createPlatformTask(McpPrincipal p, String userInput,
                                                   String requestedOutput, List<String> attachmentRefs,
                                                   String idempotencyKey) {
        return withContext(p, () -> json.convertValue(platformTasks.create(
                new PlatformContracts.CreatePlatformTask(p.taskId(), null, userInput, requestedOutput,
                        attachmentRefs, idempotencyKey)), Map.class));
    }

    public Map<String, Object> readPlatformTask(McpPrincipal p) {
        return withContext(p, () -> json.convertValue(platformTasks.read(p.taskId()), Map.class));
    }

    public Map<String, Object> executePlatformTask(McpPrincipal p, Map<String, Object> input) {
        return withContext(p, () -> json.convertValue(platformExecution.execute(p.taskId(),
                json.convertValue(input, PlatformExecutionService.ExecutionRequest.class),
                platformTasks), Map.class));
    }

    private <T> T withContext(McpPrincipal p, Supplier<T> operation) {
        RequestContextHolder.set(context(p));
        try {
            return operation.get();
        } finally {
            RequestContextHolder.clear();
        }
    }

    private RequestContext context(McpPrincipal p) {
        return new RequestContext(p.tenantId(), p.projectId(), p.actorId());
    }

    private InvestigationTaskEntity checkedTask(McpPrincipal p, long expectedVersion, int expectedRevision) {
        InvestigationTaskEntity task = tasks.findByTenantIdAndProjectIdAndTaskId(
                        p.tenantId(), p.projectId(), p.taskId())
                .orElseThrow(() -> notFound("TASK_NOT_FOUND", "task not found"));
        if (task.getVersion() != expectedVersion) throw new ApiException("STALE_TASK_VERSION", 409, "task version is stale");
        if (task.getGraphRevision() != expectedRevision) throw new ApiException("STALE_GRAPH_REVISION", 409, "graph revision is stale");
        return task;
    }

    private RiskEntity checkedRisk(McpPrincipal p, String riskId, InvestigationTaskEntity task) {
        if (!task.getRiskRef().equals(riskId)) throw notFound("RISK_NOT_FOUND", "risk not found");
        return risks.findByTenantIdAndProjectIdAndRiskId(p.tenantId(), p.projectId(), riskId)
                .orElseThrow(() -> notFound("RISK_NOT_FOUND", "risk not found"));
    }

    private Map<String, Object> taskMap(InvestigationTaskEntity task) {
        return Map.of("taskId", task.getTaskId(), "goal", task.getGoal(), "riskRef", task.getRiskRef(),
                "status", task.getStatus(), "graphRevision", task.getGraphRevision(), "version", task.getVersion());
    }

    private Map<String, Object> riskMap(RiskEntity risk) {
        return Map.of("riskId", risk.getRiskId(), "title", risk.getTitle(),
                "status", risk.getStatus().name(), "version", risk.getVersion());
    }

    private JsonNode readJson(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("stored JSON is invalid", exception);
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(code, 404, message);
    }
}
