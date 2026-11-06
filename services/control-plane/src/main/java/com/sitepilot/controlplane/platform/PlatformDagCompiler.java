package com.sitepilot.controlplane.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.error.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PlatformDagCompiler {
    private static final Map<String, Set<String>> ROLE_TOOLS = Map.of(
            "QUERY_SUPERVISOR", Set.of("read_conversation", "request_clarification"),
            "KNOWLEDGE", Set.of("search_knowledge", "get_document_chunk"),
            "DATABASE", Set.of("get_schema_snapshot", "execute_readonly_query"),
            "REVIEW", Set.of("get_review_template", "search_knowledge", "read_document"),
            "REPORT", Set.of("get_report_template", "bind_report_data", "render_report"),
            "OCR", Set.of("read_source_object", "extract_document_fields"),
            "RISK_INVESTIGATION", Set.of("start_risk_investigation", "read_risk_investigation"),
            "SYNTHESIS", Set.of("read_agent_outputs"),
            "VERIFIER", Set.of("read_agent_outputs", "verify_citations"));

    private final ObjectMapper json;

    public PlatformDagCompiler(ObjectMapper json) {
        this.json = json;
    }

    public JsonNode compile(String intent, String taskId) {
        List<AgentTask> tasks = new ArrayList<>();
        if ("HUMAN_CLARIFICATION".equals(intent)) {
            add(tasks, taskId, "QUERY_SUPERVISOR", "Request the missing project data source and output type", List.of());
            return validateAndSerialize(intent, taskId, tasks, true, List.of("ROUTE_AMBIGUOUS"));
        }

        List<String> sources = new ArrayList<>();
        if (Set.of("KNOWLEDGE_QA", "HYBRID_QA").contains(intent)) {
            sources.add(add(tasks, taskId, "KNOWLEDGE", "Retrieve project knowledge with citations", List.of()));
        }
        if (Set.of("DATABASE_QA", "HYBRID_QA").contains(intent)) {
            sources.add(add(tasks, taskId, "DATABASE", "Execute a project-scoped read-only query", List.of()));
        }
        if ("DOCUMENT_REVIEW".equals(intent)) {
            sources.add(add(tasks, taskId, "REVIEW", "Review inputs against a versioned template", List.of()));
        }
        if ("OCR_EXTRACTION".equals(intent)) {
            sources.add(add(tasks, taskId, "OCR", "Extract typed fields with source positions", List.of()));
        }
        if ("RISK_INVESTIGATION".equals(intent)) {
            sources.add(add(tasks, taskId, "RISK_INVESTIGATION", "Bridge to the existing HEA investigation", List.of()));
        }
        if ("REPORT_GENERATION".equals(intent)) {
            String knowledge = add(tasks, taskId, "KNOWLEDGE", "Retrieve report evidence and citations", List.of());
            String database = add(tasks, taskId, "DATABASE", "Retrieve authoritative report metrics", List.of());
            sources.add(add(tasks, taskId, "REPORT", "Bind and render the selected report template", List.of(knowledge, database)));
        }
        if (sources.isEmpty()) {
            throw new ApiException("PLATFORM_INTENT_UNSUPPORTED", 409, intent);
        }
        String synthesis = add(tasks, taskId, "SYNTHESIS", "Synthesize specialist outputs without inventing facts", sources);
        add(tasks, taskId, "VERIFIER", "Verify citations, permissions and output completeness", List.of(synthesis));
        return validateAndSerialize(intent, taskId, tasks, false, List.of());
    }

    private String add(List<AgentTask> tasks, String rootId, String role,
                       String objective, List<String> dependencies) {
        String id = "platform-" + rootId + "-" + role.toLowerCase().replace('_', '-');
        tasks.add(new AgentTask(id, role, objective, List.copyOf(dependencies),
                ROLE_TOOLS.get(role).stream().sorted().toList(), 30));
        return id;
    }

    private JsonNode validateAndSerialize(String intent, String taskId, List<AgentTask> tasks,
                                          boolean clarification, List<String> reasonCodes) {
        int maxTasks = 8;
        int deadlineSeconds = 120;
        List<List<String>> levels = validate(tasks, maxTasks, deadlineSeconds);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", "1.0");
        plan.put("taskId", taskId);
        plan.put("intent", intent);
        plan.put("orchestration", "DYNAMIC_MULTI_AGENT_DAG");
        plan.put("tasks", tasks);
        plan.put("levels", levels);
        plan.put("budget", Map.of("maxTasks", maxTasks, "maxToolCalls", 24));
        plan.put("deadlineSeconds", deadlineSeconds);
        plan.put("needsClarification", clarification);
        plan.put("reasonCodes", reasonCodes);
        plan.put("status", "VALIDATED");
        return json.valueToTree(plan);
    }

    List<List<String>> validate(List<AgentTask> tasks, int maxTasks, int deadlineSeconds) {
        if (tasks.isEmpty() || tasks.size() > maxTasks || deadlineSeconds <= 0 || deadlineSeconds > 3600) {
            throw new ApiException("PLATFORM_BUDGET_INVALID", 409, "task budget or deadline invalid");
        }
        Map<String, AgentTask> byId = new HashMap<>();
        for (AgentTask task : tasks) {
            if (byId.put(task.taskId(), task) != null) {
                throw new ApiException("PLATFORM_TASK_DUPLICATE", 409, task.taskId());
            }
            Set<String> allowed = ROLE_TOOLS.get(task.role());
            if (allowed == null || !allowed.containsAll(task.allowedTools())) {
                throw new ApiException("PLATFORM_TOOL_DENIED", 403, task.role());
            }
            if (task.timeoutSeconds() <= 0 || task.timeoutSeconds() > deadlineSeconds) {
                throw new ApiException("PLATFORM_DEADLINE_INVALID", 409, task.taskId());
            }
        }
        for (AgentTask task : tasks) {
            if (!byId.keySet().containsAll(task.dependencies())) {
                throw new ApiException("PLATFORM_DEPENDENCY_MISSING", 409, task.taskId());
            }
        }
        List<List<String>> levels = new ArrayList<>();
        Set<String> completed = new HashSet<>();
        while (completed.size() < tasks.size()) {
            List<String> ready = tasks.stream()
                    .filter(task -> !completed.contains(task.taskId()))
                    .filter(task -> completed.containsAll(task.dependencies()))
                    .map(AgentTask::taskId).toList();
            if (ready.isEmpty()) throw new ApiException("PLATFORM_DAG_CYCLE", 409, "cycle detected");
            levels.add(ready);
            completed.addAll(ready);
        }
        return levels;
    }

    public record AgentTask(String taskId, String role, String objective,
                            List<String> dependencies, List<String> allowedTools,
                            int timeoutSeconds) {}
}
