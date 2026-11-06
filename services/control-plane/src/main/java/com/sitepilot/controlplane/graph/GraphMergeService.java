package com.sitepilot.controlplane.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.contracts.GraphDelta;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GraphMergeService {
    private static final Set<String> SOURCE_TYPES = Set.of(
            "event", "business_record", "sensor_snapshot", "image", "document_clause",
            "human_statement", "tool_diagnostic");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public GraphMergeService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> merge(RequestContext context, String taskId, int expectedRevision, GraphDelta delta) {
        TaskRow task = lockTask(context, taskId);
        require(delta != null && taskId.equals(delta.taskId()), "GRAPH_TASK_MISMATCH", "delta task mismatch");
        require("1.0".equals(delta.schemaVersion()) || "2.0".equals(delta.schemaVersion()),
                "GRAPH_DELTA_VERSION_UNSUPPORTED", "unsupported graph delta version");
        String deltaJson = write(delta);
        String deltaHash = hash(deltaJson);
        List<Map<String, Object>> prior = jdbc.queryForList(
                "select revision, delta_hash from graph_revisions where tenant_id=? and project_id=? and task_id=? and delta_id=?",
                context.tenantId(), context.projectId(), taskId, delta.deltaId());
        if (!prior.isEmpty()) {
            require(deltaHash.equals(prior.get(0).get("delta_hash")), "GRAPH_DELTA_ID_CONFLICT",
                    "delta id already has different semantics");
            return Map.of("taskId", taskId, "revision", ((Number) prior.get(0).get("revision")).intValue(),
                    "applied", false, "idempotent", true);
        }
        require(delta.baseRevision() == expectedRevision && task.revision == expectedRevision,
                "STALE_GRAPH_REVISION", "graph revision is stale");

        int revision = expectedRevision + 1;
        Map<String, JsonNode> newHypotheses = index(delta.newHypotheses(), "hypothesis_id", "HYPOTHESIS_ID_REQUIRED");
        Map<String, JsonNode> newEvidence = index(delta.newEvidence(), "evidence_id", "EVIDENCE_ID_REQUIRED");
        Map<String, JsonNode> newQuestions = index(delta.newQuestions(), "question_id", "QUESTION_ID_REQUIRED");
        Map<String, JsonNode> newConflicts = index(delta.newConflicts(), "conflict_id", "CONFLICT_ID_REQUIRED");
        Map<String, JsonNode> newActions = index(delta.actionProposals(), "action_id", "ACTION_ID_REQUIRED");

        Set<String> hypotheses = existingIds(context, taskId, "HYPOTHESIS");
        Set<String> evidence = existingIds(context, taskId, "EVIDENCE");
        hypotheses.addAll(newHypotheses.keySet());
        evidence.addAll(newEvidence.keySet());
        validateEvidence(task, delta, hypotheses);
        validateReferences(delta, hypotheses, evidence);

        insertNodes(context, taskId, revision, "HYPOTHESIS", newHypotheses);
        insertNodes(context, taskId, revision, "EVIDENCE", newEvidence);
        insertNodes(context, taskId, revision, "QUESTION", newQuestions);
        insertNodes(context, taskId, revision, "CONFLICT", newConflicts);
        insertNodes(context, taskId, revision, "ACTION_PROPOSAL", newActions);
        insertEdges(context, taskId, revision, delta.newEvidence());

        jdbc.update("insert into graph_revisions (tenant_id,project_id,task_id,revision,parent_revision,delta_id,delta_hash,delta,actor,created_at) values (?,?,?,?,?,?,?,cast(? as jsonb),?,?)",
                context.tenantId(), context.projectId(), taskId, revision, expectedRevision, delta.deltaId(),
                deltaHash, deltaJson, context.actor(), OffsetDateTime.now());
        int updated = jdbc.update("update investigation_tasks set graph_revision=?, version=version+1 where tenant_id=? and project_id=? and task_id=? and graph_revision=?",
                revision, context.tenantId(), context.projectId(), taskId, expectedRevision);
        require(updated == 1, "STALE_GRAPH_REVISION", "graph revision changed concurrently");
        return Map.of("taskId", taskId, "revision", revision, "applied", true, "idempotent", false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> read(RequestContext context, String taskId) {
        TaskRow task = task(context, taskId, false);
        List<Map<String, Object>> nodes = jdbc.queryForList(
                "select node_id as \"nodeId\", node_type as \"nodeType\", payload from graph_nodes where tenant_id=? and project_id=? and task_id=? order by id",
                context.tenantId(), context.projectId(), taskId);
        return Map.of("taskId", taskId, "revision", task.revision, "nodes", nodes);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> completionGate(RequestContext context, String taskId, JsonNode request) {
        TaskRow task = task(context, taskId, false);
        Set<String> required = textSet(request.path("requiredEvidenceCategories"));
        int minSources = request.path("minIndependentSources").asInt(2);
        List<Map<String, Object>> evidenceRows = jdbc.queryForList(
                "select payload from graph_nodes where tenant_id=? and project_id=? and task_id=? and node_type='EVIDENCE'",
                context.tenantId(), context.projectId(), taskId);
        Set<String> categories = new HashSet<>();
        Set<String> sources = new HashSet<>();
        List<String> failures = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (Map<String, Object> row : evidenceRows) {
            JsonNode item = readJson(row.get("payload"));
            if (!task.riskRef.equals(item.path("subject_ref").asText())) failures.add("WRONG_EVIDENCE_SUBJECT");
            if (!item.hasNonNull("valid_until") || OffsetDateTime.parse(item.path("valid_until").asText()).isBefore(now)) {
                failures.add("STALE_EVIDENCE");
            }
            categories.add(item.path("category").asText());
            sources.add(item.path("source_ref").asText());
        }
        if (!categories.containsAll(required)) failures.add("REQUIRED_EVIDENCE_MISSING");
        if (sources.size() < minSources) failures.add("INDEPENDENT_SOURCES_MISSING");
        Integer critical = jdbc.queryForObject(
                "select count(*) from graph_nodes where tenant_id=? and project_id=? and task_id=? and node_type='CONFLICT' and payload->>'severity'='critical' and payload->>'status'='open'",
                Integer.class, context.tenantId(), context.projectId(), taskId);
        if (critical != null && critical > 0) failures.add("CRITICAL_CONFLICT_OPEN");
        boolean counterexample = bool(request, "counterexampleSearchCompleted");
        boolean compliance = bool(request, "compliancePassed");
        boolean verifier = bool(request, "verifierApproved");
        boolean uncertainty = bool(request, "uncertaintyRepresented");
        boolean budget = bool(request, "withinBudget");
        if (!counterexample) failures.add("COUNTEREXAMPLE_SEARCH_REQUIRED");
        if (!compliance) failures.add("COMPLIANCE_REQUIRED");
        if (!verifier) failures.add("VERIFIER_REQUIRED");
        if (!uncertainty) failures.add("UNCERTAINTY_REQUIRED");
        if (!budget) failures.add("BUDGET_EXHAUSTED");
        return Map.of("taskId", taskId, "revision", task.revision, "complete", failures.isEmpty(),
                "failureCodes", failures.stream().distinct().sorted().toList(), "modelDoneIgnored", true);
    }

    private void validateEvidence(TaskRow task, GraphDelta delta, Set<String> hypotheses) {
        OffsetDateTime now = OffsetDateTime.now();
        for (JsonNode item : delta.newEvidence()) {
            requiredText(item, "evidence_id", "EVIDENCE_ID_REQUIRED");
            String sourceType = requiredText(item, "source_type", "EVIDENCE_SOURCE_REQUIRED");
            require(SOURCE_TYPES.contains(sourceType), "EVIDENCE_SOURCE_INVALID", "invalid source type");
            requiredText(item, "source_ref", "EVIDENCE_SOURCE_REQUIRED");
            requiredText(item, "source_version", "EVIDENCE_SOURCE_VERSION_REQUIRED");
            requiredText(item, "observed_at", "EVIDENCE_OBSERVED_AT_REQUIRED");
            requiredText(item, "ingested_at", "EVIDENCE_INGESTED_AT_REQUIRED");
            require(task.riskRef.equals(requiredText(item, "subject_ref", "EVIDENCE_SUBJECT_REQUIRED")),
                    "EVIDENCE_SUBJECT_MISMATCH", "evidence subject is not the task risk");
            JsonNode provenance = item.path("provenance");
            requiredText(provenance, "tool_call_id", "EVIDENCE_PROVENANCE_REQUIRED");
            requiredText(provenance, "tool_name", "EVIDENCE_PROVENANCE_REQUIRED");
            require(delta.agentTaskId().equals(requiredText(provenance, "agent_task_id", "EVIDENCE_PROVENANCE_REQUIRED")),
                    "EVIDENCE_AGENT_TASK_MISMATCH", "provenance agent task mismatch");
            require(item.hasNonNull("valid_until"), "EVIDENCE_VALID_UNTIL_REQUIRED", "valid_until is required");
            require(!OffsetDateTime.parse(item.path("valid_until").asText()).isBefore(now),
                    "EVIDENCE_EXPIRED", "expired evidence cannot be merged");
            for (String id : textSet(item.path("supports"))) require(hypotheses.contains(id), "GRAPH_REFERENCE_MISSING", "support target missing");
            for (String id : textSet(item.path("contradicts"))) require(hypotheses.contains(id), "GRAPH_REFERENCE_MISSING", "contradiction target missing");
        }
    }

    private void validateReferences(GraphDelta delta, Set<String> hypotheses, Set<String> evidence) {
        for (JsonNode item : delta.newQuestions()) {
            for (String id : textSet(item.path("distinguishes"))) require(hypotheses.contains(id), "GRAPH_REFERENCE_MISSING", "question target missing");
            for (String id : textSet(item.path("answer_evidence_refs"))) require(evidence.contains(id), "GRAPH_REFERENCE_MISSING", "answer evidence missing");
        }
        for (JsonNode item : delta.newConflicts()) {
            for (String id : textSet(item.path("evidence_refs"))) require(evidence.contains(id), "GRAPH_REFERENCE_MISSING", "conflict evidence missing");
        }
        for (JsonNode item : delta.hypothesisUpdates()) {
            require(hypotheses.contains(requiredText(item, "hypothesis_id", "GRAPH_REFERENCE_MISSING")), "GRAPH_REFERENCE_MISSING", "hypothesis update target missing");
            for (String id : textSet(item.path("evidence_refs"))) require(evidence.contains(id), "GRAPH_REFERENCE_MISSING", "update evidence missing");
        }
    }

    private void insertNodes(RequestContext c, String taskId, int revision, String type, Map<String, JsonNode> nodes) {
        for (var entry : nodes.entrySet()) {
            String payload = write(entry.getValue());
            String semanticHash = hash(payload);
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "select semantic_hash from graph_nodes where tenant_id=? and project_id=? and task_id=? and node_type=? and node_id=?",
                    c.tenantId(), c.projectId(), taskId, type, entry.getKey());
            if (!existing.isEmpty()) {
                require(semanticHash.equals(existing.get(0).get("semantic_hash")), type + "_ID_CONFLICT", "node id has different semantics");
                continue;
            }
            jdbc.update("insert into graph_nodes (tenant_id,project_id,task_id,node_id,node_type,semantic_hash,payload,created_revision,created_at) values (?,?,?,?,?,?,cast(? as jsonb),?,?)",
                    c.tenantId(), c.projectId(), taskId, entry.getKey(), type, semanticHash, payload, revision, OffsetDateTime.now());
        }
    }

    private void insertEdges(RequestContext c, String taskId, int revision, List<JsonNode> items) {
        for (JsonNode item : items) {
            String evidenceId = item.path("evidence_id").asText();
            for (String id : textSet(item.path("supports"))) edge(c, taskId, revision, evidenceId, id, "SUPPORTS");
            for (String id : textSet(item.path("contradicts"))) edge(c, taskId, revision, evidenceId, id, "CONTRADICTS");
        }
    }

    private void edge(RequestContext c, String taskId, int revision, String evidenceId, String hypothesisId, String relation) {
        jdbc.update("insert into evidence_edges (tenant_id,project_id,task_id,evidence_id,hypothesis_id,relation,created_revision) values (?,?,?,?,?,?,?) on conflict do nothing",
                c.tenantId(), c.projectId(), taskId, evidenceId, hypothesisId, relation, revision);
    }

    private Map<String, JsonNode> index(List<JsonNode> values, String field, String code) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String id = requiredText(value, field, code);
            JsonNode previous = result.putIfAbsent(id, value);
            require(previous == null || previous.equals(value), code.replace("REQUIRED", "CONFLICT"), "duplicate id in delta");
        }
        return result;
    }

    private Set<String> existingIds(RequestContext c, String taskId, String type) {
        return new HashSet<>(jdbc.queryForList(
                "select node_id from graph_nodes where tenant_id=? and project_id=? and task_id=? and node_type=?",
                String.class, c.tenantId(), c.projectId(), taskId, type));
    }

    private TaskRow lockTask(RequestContext c, String taskId) { return task(c, taskId, true); }
    private TaskRow task(RequestContext c, String taskId, boolean lock) {
        String sql = "select graph_revision,risk_ref from investigation_tasks where tenant_id=? and project_id=? and task_id=?" + (lock ? " for update" : "");
        List<TaskRow> rows = jdbc.query(sql, (rs, n) -> new TaskRow(rs.getInt(1), rs.getString(2)),
                c.tenantId(), c.projectId(), taskId);
        if (rows.isEmpty()) throw new ApiException("TASK_NOT_FOUND", 404, "task not found");
        return rows.get(0);
    }

    private String requiredText(JsonNode node, String field, String code) {
        String value = node.path(field).asText();
        require(!value.isBlank(), code, field + " is required");
        return value;
    }
    private Set<String> textSet(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (node.isArray()) node.forEach(item -> values.add(item.asText()));
        return values;
    }
    private boolean bool(JsonNode node, String name) { return node.path(name).asBoolean(false); }
    private JsonNode readJson(Object value) {
        try { return json.readTree(String.valueOf(value)); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("invalid graph payload", e); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void require(boolean condition, String code, String message) {
        if (!condition) throw new ApiException(code, 409, message);
    }
    private record TaskRow(int revision, String riskRef) {}
}
