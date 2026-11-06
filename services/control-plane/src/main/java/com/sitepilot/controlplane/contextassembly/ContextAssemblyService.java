package com.sitepilot.controlplane.contextassembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.error.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContextAssemblyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ContextSnapshotRepository snapshots;
    private final EvidenceRankingPolicy ranking;
    private final ContextBudgetPolicy budgets;
    private final ContextPhaseResolver phases;
    private final ContextSlicePolicy slices;
    private final ContextDeltaPolicy deltas;
    private final MeterRegistry metrics;

    public ContextAssemblyService(JdbcTemplate jdbc, ObjectMapper json,
                                  ContextSnapshotRepository snapshots,
                                  EvidenceRankingPolicy ranking,
                                  ContextBudgetPolicy budgets,
                                  ContextPhaseResolver phases,
                                  ContextSlicePolicy slices,
                                  ContextDeltaPolicy deltas,
                                  MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.snapshots = snapshots;
        this.ranking = ranking;
        this.budgets = budgets;
        this.phases = phases;
        this.slices = slices;
        this.deltas = deltas;
        this.metrics = metrics;
    }

    @Transactional
    public Map<String, Object> assemble(RequestContext context, String taskId,
                                        String principalRole, String agentTaskId,
                                        long expectedTaskVersion, int expectedGraphRevision,
                                        String contextPolicyVersion, String promptVersion) {
        long started = System.nanoTime();
        require("context-policy.v1".equals(contextPolicyVersion)
                        || "context-policy.v2".equals(contextPolicyVersion),
                "CONTEXT_POLICY_UNSUPPORTED", "unsupported context policy");
        TaskRow task = task(context, taskId);
        require(task.version == expectedTaskVersion, "STALE_TASK_VERSION", "task version is stale");
        require(task.revision == expectedGraphRevision,
                "STALE_GRAPH_REVISION", "graph revision is stale");
        AgentTaskRow agentTask = agentTask(context, taskId, expectedGraphRevision, agentTaskId);
        require(agentTask.role.equalsIgnoreCase(principalRole),
                "CONTEXT_ROLE_MISMATCH", "agent task does not belong to authenticated role");

        ContextSnapshotEntity parent = "context-policy.v2".equals(contextPolicyVersion)
                ? snapshots.findTopByTenantIdAndProjectIdAndTaskIdAndAgentTaskIdAndContextPolicyVersionAndPromptVersionAndGraphRevisionLessThanOrderByGraphRevisionDesc(
                        context.tenantId(), context.projectId(), taskId, agentTaskId,
                        contextPolicyVersion, promptVersion, expectedGraphRevision).orElse(null)
                : null;
        BuildResult built = build(context, task, agentTask, contextPolicyVersion, promptVersion, parent);
        var existing = snapshots
                .findByTenantIdAndProjectIdAndTaskIdAndAgentTaskIdAndGraphRevisionAndContextPolicyVersionAndPromptVersion(
                        context.tenantId(), context.projectId(), taskId, agentTaskId,
                        expectedGraphRevision, contextPolicyVersion, promptVersion);
        if (existing.isPresent()) {
            require(existing.get().getContextDigest().equals(built.digest),
                    "CONTEXT_SNAPSHOT_CONFLICT", "same context key produced a different digest");
            recordMetrics(built, agentTask.role, true, started);
            return response(existing.get(), false);
        }

        ContextSnapshotEntity entity = new ContextSnapshotEntity(
                context.tenantId(), context.projectId(), taskId, agentTaskId,
                built.contextId, expectedGraphRevision, agentTask.role,
                agentTask.verificationStage, contextPolicyVersion, promptVersion,
                "sha256:" + hash(agentTask.objective), built.digest,
                write(built.pack), write(built.pack.path("manifest")),
                built.estimatedTokens, "READY", built.contextMode,
                built.parent == null ? null : built.parent.getContextId(),
                built.parent == null ? null : built.parent.getContextDigest(),
                built.parent == null ? null : built.parent.getGraphRevision(),
                built.chainDepth);
        try {
            ContextSnapshotEntity saved = snapshots.saveAndFlush(entity);
            recordMetrics(built, agentTask.role, false, started);
            return response(saved, true);
        } catch (DataIntegrityViolationException conflict) {
            ContextSnapshotEntity concurrent = snapshots
                    .findByTenantIdAndProjectIdAndTaskIdAndAgentTaskIdAndGraphRevisionAndContextPolicyVersionAndPromptVersion(
                            context.tenantId(), context.projectId(), taskId, agentTaskId,
                            expectedGraphRevision, contextPolicyVersion, promptVersion)
                    .orElseThrow(() -> conflict);
            require(concurrent.getContextDigest().equals(built.digest),
                    "CONTEXT_SNAPSHOT_CONFLICT", "concurrent context digest differs");
            recordMetrics(built, agentTask.role, true, started);
            return response(concurrent, false);
        }
    }

    private BuildResult build(RequestContext context, TaskRow task, AgentTaskRow agentTask,
                              String policyVersion, String promptVersion,
                              ContextSnapshotEntity parent) {
        EventRisk eventRisk = eventRisk(context, task.riskRef);
        List<NodeRow> nodes = nodes(context, task.taskId, task.revision);
        Map<String, List<Relation>> relations = relations(context, task.taskId, task.revision);

        List<NodeRow> hypotheses = nodes.stream()
                .filter(node -> node.type.equals("HYPOTHESIS"))
                .filter(node -> !"rejected".equalsIgnoreCase(node.payload.path("status").asText()))
                .toList();
        List<NodeRow> conflicts = nodes.stream()
                .filter(node -> node.type.equals("CONFLICT"))
                .filter(node -> "open".equalsIgnoreCase(node.payload.path("status").asText("open")))
                .toList();
        List<NodeRow> questions = nodes.stream()
                .filter(node -> node.type.equals("QUESTION"))
                .filter(node -> !"answered".equalsIgnoreCase(node.payload.path("status").asText()))
                .toList();
        List<NodeRow> actions = nodes.stream()
                .filter(node -> node.type.equals("ACTION_PROPOSAL"))
                .toList();
        ContextPhaseResolver.ContextPhase phase = phases.resolve(agentTask.role,
                agentTask.verificationStage, conflicts.stream().map(NodeRow::payload).toList(),
                actions.stream().map(NodeRow::payload).toList());
        Set<String> criticalEvidence = criticalEvidence(conflicts);
        boolean v2 = "context-policy.v2".equals(policyVersion);

        Set<String> mandatoryEvidence = mandatoryEvidence(hypotheses, conflicts, relations);
        Set<String> expiredNodeIds = new HashSet<>();
        Map<String, List<String>> selectionReasons = new HashMap<>();
        int expired = 0;
        List<ContextBudgetPolicy.RankedEvidence> ranked = new ArrayList<>();
        for (NodeRow node : nodes) {
            if (!node.type.equals("EVIDENCE")) continue;
            String freshness = ranking.freshnessLabel(node.payload);
            if ("EXPIRED".equals(freshness)) {
                expired++;
                expiredNodeIds.add(node.id);
                continue;
            }
            List<Relation> itemRelations = relations.getOrDefault(node.id, List.of());
            List<String> relationNames = itemRelations.stream().map(Relation::relation).toList();
            boolean mandatory = mandatoryEvidence.contains(node.id);
            if (v2 && !slices.include(agentTask.role, agentTask.capability, node.payload,
                    relationNames, phase, mandatory)) continue;
            boolean critical = criticalEvidence.contains(node.id);
            String reason = slices.primaryReason(node.payload, relationNames, phase, critical, mandatory);
            selectionReasons.put(node.id, List.of(reason));
            int priority = priority(reason);
            ContextBudgetPolicy.Partition partition = partition(reason);
            double score = ranking.score(node.payload, agentTask.capability, 1);
            ranked.add(new ContextBudgetPolicy.RankedEvidence(
                    evidenceCard(node, itemRelations, freshness, score, mandatory, reason),
                    score, mandatory, priority, partition));
        }
        ranked.sort(Comparator.comparingInt(ContextBudgetPolicy.RankedEvidence::priority)
                .thenComparing(Comparator.comparingDouble(ContextBudgetPolicy.RankedEvidence::score).reversed()));

        ArrayNode hypothesisArray = hypotheses(hypotheses, relations);
        ArrayNode conflictArray = payloads(conflicts);
        ArrayNode questionArray = includeQuestions(agentTask) ? payloads(questions) : json.createArrayNode();
        ArrayNode actionArray = includeActions(agentTask) ? payloads(actions) : json.createArrayNode();
        ObjectNode eventSummary = eventSummary(eventRisk);
        ObjectNode riskSummary = riskSummary(eventRisk);
        ObjectNode fixed = json.createObjectNode();
        fixed.set("event", eventSummary);
        fixed.set("risk", riskSummary);
        fixed.set("hypotheses", hypothesisArray);
        fixed.set("conflicts", conflictArray);
        fixed.set("questions", questionArray);
        fixed.set("actions", actionArray);
        int maxTokens = maxTokens(agentTask);
        int baseTokens = budgets.tokens(fixed) + budgets.tokens(agentTask.objective)
                + budgets.tokens(agentTask.allowedTools) + 300;
        ContextBudgetPolicy.BudgetResult selected = budgets.apply(ranked, baseTokens, maxTokens);

        LinkedHashSet<String> included = new LinkedHashSet<>();
        hypotheses.forEach(item -> included.add(item.id));
        conflicts.forEach(item -> included.add(item.id));
        if (includeQuestions(agentTask)) questions.forEach(item -> included.add(item.id));
        if (includeActions(agentTask)) actions.forEach(item -> included.add(item.id));
        ArrayNode evidenceArray = json.createArrayNode();
        selected.selected().forEach(item -> {
            evidenceArray.add(item.card());
            included.add(item.card().path("evidence_id").asText());
        });

        int irrelevant = Math.max(0, nodes.size() - included.size() - expired - selected.trimmed());
        String contextKey = task.taskId + ":" + agentTask.taskId + ":" + task.revision
                + ":" + policyVersion + ":" + promptVersion;
        String contextId = "ctx-" + task.taskId + "-r" + task.revision + "-" + hash(contextKey).substring(0, 12);
        ObjectNode pack = json.createObjectNode();
        pack.put("schema_version", v2 ? "2.0" : "1.0");
        pack.put("context_id", contextId);
        pack.put("task_id", task.taskId);
        pack.put("agent_task_id", agentTask.taskId);
        pack.put("graph_revision", task.revision);
        pack.put("role", agentTask.role);
        if (agentTask.verificationStage == null) pack.putNull("verification_stage");
        else pack.put("verification_stage", agentTask.verificationStage);
        pack.put("objective", agentTask.objective);
        pack.set("event_summary", eventSummary);
        pack.set("risk_summary", riskSummary);
        pack.set("active_hypotheses", hypothesisArray);
        pack.set("evidence_cards", evidenceArray);
        if (v2) pack.set("evidence_handles", evidenceHandles(evidenceArray));
        pack.set("open_conflicts", conflictArray);
        pack.set("unresolved_questions", questionArray);
        pack.set("action_context", actionArray);
        pack.set("allowed_capabilities", json.valueToTree(List.of(agentTask.capability)));
        pack.set("allowed_tools", json.valueToTree(agentTask.allowedTools));
        ObjectNode budget = pack.putObject("budget");
        budget.put("max_model_calls", 1);
        budget.put("max_tool_calls", agentTask.allowedTools.size());
        budget.put("max_context_tokens", maxTokens);
        ObjectNode manifest = pack.putObject("manifest");
        manifest.put("context_policy_version", policyVersion);
        manifest.put("prompt_version", promptVersion);
        manifest.set("included_node_ids", json.valueToTree(included));
        ObjectNode excluded = manifest.putObject("excluded_counts");
        excluded.put("expired", expired);
        excluded.put("irrelevant", irrelevant);
        excluded.put("budget_trimmed", selected.trimmed());
        if (v2) {
            pack.put("context_phase", phase.name());
            pack.set("reasoning_frontier", reasoningFrontier(hypotheses, questions, conflicts, phase));
            ObjectNode reasons = manifest.putObject("selection_reasons");
            selectionReasons.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    reasons.set(entry.getKey(), json.valueToTree(entry.getValue())));
            ObjectNode allocation = manifest.putObject("token_allocation");
            allocation.put("task", budgets.tokens(eventSummary) + budgets.tokens(riskSummary)
                    + budgets.tokens(agentTask.objective));
            allocation.put("reasoning_frontier", budgets.tokens(pack.path("reasoning_frontier")));
            allocation.put("counterevidence", selected.allocation()
                    .get(ContextBudgetPolicy.Partition.COUNTEREVIDENCE));
            allocation.put("supporting_evidence", selected.allocation()
                    .get(ContextBudgetPolicy.Partition.SUPPORTING_EVIDENCE));
            allocation.put("background", selected.allocation()
                    .get(ContextBudgetPolicy.Partition.BACKGROUND));
            ContextDeltaPolicy.DeltaDecision decision = deltas.apply(pack, parent, expiredNodeIds);
            manifest.put("estimated_tokens", budgets.tokens(pack));
            manifest.put("context_digest", "");
            String digest = "sha256:" + hash(write(pack));
            manifest.put("context_digest", digest);
            return new BuildResult(contextId, pack, digest, budgets.tokens(pack),
                    decision.mode(), decision.parent(), decision.chainDepth());
        }
        manifest.put("estimated_tokens", selected.estimatedTokens());
        manifest.put("context_digest", "");
        String digest = "sha256:" + hash(write(pack));
        manifest.put("context_digest", digest);
        return new BuildResult(contextId, pack, digest, selected.estimatedTokens(),
                "FULL", null, 0);
    }

    private Set<String> mandatoryEvidence(List<NodeRow> hypotheses, List<NodeRow> conflicts,
                                          Map<String, List<Relation>> relations) {
        Set<String> result = new HashSet<>();
        for (NodeRow hypothesis : hypotheses) {
            relations.forEach((evidenceId, values) -> {
                if (values.stream().anyMatch(item -> item.hypothesisId.equals(hypothesis.id)
                        && item.relation.equals("SUPPORTS"))) result.add(evidenceId);
                if (values.stream().anyMatch(item -> item.hypothesisId.equals(hypothesis.id)
                        && item.relation.equals("CONTRADICTS"))) result.add(evidenceId);
            });
        }
        for (NodeRow conflict : conflicts) {
            conflict.payload.path("evidence_refs").forEach(item -> result.add(item.asText()));
        }
        return result;
    }

    private ObjectNode evidenceCard(NodeRow node, List<Relation> relations, String freshness,
                                    double score, boolean mandatory, String selectionReason) {
        ObjectNode card = json.createObjectNode();
        card.put("evidence_id", node.id);
        card.put("claim", node.payload.path("claim").asText("Structured evidence"));
        ArrayNode relationArray = card.putArray("relations");
        for (Relation relation : relations) {
            ObjectNode value = relationArray.addObject();
            value.put("relation", relation.relation);
            value.put("hypothesis_ref", relation.hypothesisId);
        }
        card.put("observed_at", node.payload.path("observed_at").asText(OffsetDateTime.now().toString()));
        card.put("freshness", freshness);
        card.put("reliability", node.payload.path("reliability").asText("unknown"));
        card.put("source_type", node.payload.path("source_type").asText("unknown"));
        card.put("source_ref", node.payload.path("source_ref").asText("unknown"));
        card.put("source_version", node.payload.path("source_version").asText("unknown"));
        card.put("tool_call_id", node.payload.path("provenance").path("tool_call_id").asText("unknown"));
        if (node.payload.has("structured_value")) card.set("structured_value", node.payload.path("structured_value"));
        card.put("score", Math.round(score * 1000.0) / 1000.0);
        card.put("mandatory", mandatory);
        card.put("selection_reason", selectionReason);
        card.put("source_digest", "sha256:" + hash(write(node.payload)));
        return card;
    }

    private Set<String> criticalEvidence(List<NodeRow> conflicts) {
        Set<String> result = new HashSet<>();
        conflicts.stream()
                .filter(item -> "critical".equalsIgnoreCase(item.payload.path("severity").asText()))
                .forEach(item -> item.payload.path("evidence_refs").forEach(ref -> result.add(ref.asText())));
        return result;
    }

    private int priority(String reason) {
        return switch (reason) {
            case "CRITICAL_CONFLICT" -> 0;
            case "COUNTEREVIDENCE" -> 1;
            case "CLOSURE_PREREQUISITE" -> 2;
            case "REQUIRED_EVIDENCE" -> 3;
            default -> 5;
        };
    }

    private ContextBudgetPolicy.Partition partition(String reason) {
        return switch (reason) {
            case "CRITICAL_CONFLICT", "COUNTEREVIDENCE" -> ContextBudgetPolicy.Partition.COUNTEREVIDENCE;
            case "REQUIRED_EVIDENCE", "CLOSURE_PREREQUISITE" -> ContextBudgetPolicy.Partition.SUPPORTING_EVIDENCE;
            default -> ContextBudgetPolicy.Partition.BACKGROUND;
        };
    }

    private ArrayNode evidenceHandles(ArrayNode cards) {
        ArrayNode result = json.createArrayNode();
        cards.forEach(card -> {
            ObjectNode handle = result.addObject();
            handle.put("evidence_id", card.path("evidence_id").asText());
            handle.put("detail_tool", "get_evidence_detail");
            handle.put("source_digest", card.path("source_digest").asText());
        });
        return result;
    }

    private ObjectNode reasoningFrontier(List<NodeRow> hypotheses, List<NodeRow> questions,
                                         List<NodeRow> conflicts,
                                         ContextPhaseResolver.ContextPhase phase) {
        ObjectNode result = json.createObjectNode();
        result.set("hypothesis_ids", json.valueToTree(hypotheses.stream().map(NodeRow::id).toList()));
        result.set("question_ids", json.valueToTree(questions.stream().map(NodeRow::id).toList()));
        result.set("critical_conflict_ids", json.valueToTree(conflicts.stream()
                .filter(item -> "critical".equalsIgnoreCase(item.payload.path("severity").asText()))
                .map(NodeRow::id).toList()));
        List<String> unmet = new ArrayList<>();
        if (!questions.isEmpty()) unmet.add("UNRESOLVED_QUESTIONS");
        if (!conflicts.isEmpty()) unmet.add("OPEN_CONFLICTS");
        if (phase == ContextPhaseResolver.ContextPhase.CLOSURE) unmet.add("CURRENT_POST_ACTION_OBSERVATION");
        result.set("unmet_completion_conditions", json.valueToTree(unmet));
        return result;
    }

    private ArrayNode hypotheses(List<NodeRow> values, Map<String, List<Relation>> relations) {
        ArrayNode result = json.createArrayNode();
        for (NodeRow node : values) {
            int supports = 0;
            int contradicts = 0;
            for (List<Relation> edges : relations.values()) {
                supports += (int) edges.stream().filter(item -> item.hypothesisId.equals(node.id)
                        && item.relation.equals("SUPPORTS")).count();
                contradicts += (int) edges.stream().filter(item -> item.hypothesisId.equals(node.id)
                        && item.relation.equals("CONTRADICTS")).count();
            }
            ObjectNode item = result.addObject();
            item.put("hypothesis_id", node.id);
            item.put("claim", node.payload.path("statement").asText(
                    node.payload.path("claim").asText("Unspecified hypothesis")));
            item.put("state", node.payload.path("status").asText("active"));
            item.put("support_count", supports);
            item.put("contradiction_count", contradicts);
        }
        return result;
    }

    private ArrayNode payloads(List<NodeRow> rows) {
        ArrayNode result = json.createArrayNode();
        rows.forEach(row -> result.add(row.payload));
        return result;
    }

    private ObjectNode eventSummary(EventRisk value) {
        ObjectNode result = json.createObjectNode();
        result.put("event_type", value.eventType);
        result.put("occurred_at", value.occurredAt.toString());
        result.set("attributes", value.attributes);
        result.put("source_event_ref", value.eventId);
        return result;
    }

    private ObjectNode riskSummary(EventRisk value) {
        ObjectNode result = json.createObjectNode();
        result.put("risk_ref", value.riskId);
        result.put("title", value.title);
        result.put("status", value.status);
        return result;
    }

    private boolean includeQuestions(AgentTaskRow task) {
        return !task.role.equals("compliance") || task.verificationStage != null;
    }

    private boolean includeActions(AgentTaskRow task) {
        return task.role.equals("supervisor") || "closure".equals(task.verificationStage);
    }

    private int maxTokens(AgentTaskRow task) {
        if ("closure".equals(task.verificationStage)) return 4500;
        if (task.role.equals("compliance")) return 3500;
        return 4000;
    }

    private TaskRow task(RequestContext context, String taskId) {
        List<TaskRow> rows = jdbc.query(
                "select task_id,risk_ref,goal,graph_revision,version from investigation_tasks where tenant_id=? and project_id=? and task_id=?",
                (rs, row) -> new TaskRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getLong(5)),
                context.tenantId(), context.projectId(), taskId);
        if (rows.isEmpty()) throw new ApiException("TASK_NOT_FOUND", 404, "task not found");
        return rows.get(0);
    }

    private AgentTaskRow agentTask(RequestContext context, String taskId, int revision,
                                   String agentTaskId) {
        List<String> rows = jdbc.queryForList(
                "select compiled_dag::text from routing_decisions where tenant_id=? and project_id=? and task_id=? and graph_revision=? order by created_at desc limit 1",
                String.class, context.tenantId(), context.projectId(), taskId, revision);
        if (rows.isEmpty()) throw new ApiException("ROUTING_DECISION_NOT_FOUND", 404,
                "routing decision required before context assembly");
        JsonNode dag = read(rows.get(0));
        for (JsonNode item : dag.path("tasks")) {
            if (!agentTaskId.equals(item.path("task_id").asText())) continue;
            List<String> tools = new ArrayList<>();
            item.path("allowed_tools").forEach(tool -> tools.add(tool.asText()));
            String capability = capability(agentTaskId, revision);
            return new AgentTaskRow(agentTaskId, item.path("role").asText(),
                    item.path("objective").asText(), tools,
                    item.path("verification_stage").isNull() ? null
                            : item.path("verification_stage").asText(null), capability);
        }
        throw new ApiException("AGENT_TASK_NOT_FOUND", 404, "agent task is not in compiled DAG");
    }

    private String capability(String agentTaskId, int revision) {
        String prefix = "route-" + revision + "-";
        return agentTaskId.startsWith(prefix) ? agentTaskId.substring(prefix.length()) : "unknown";
    }

    private EventRisk eventRisk(RequestContext context, String riskRef) {
        List<EventRisk> rows = jdbc.query(
                "select r.risk_id,r.title,r.status,e.event_id,e.event_type,e.occurred_at,e.attributes::text "
                        + "from risks r join source_events e on e.tenant_id=r.tenant_id and e.project_id=r.project_id and e.event_id=r.source_event_id "
                        + "where r.tenant_id=? and r.project_id=? and r.risk_id=?",
                (rs, row) -> new EventRisk(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class),
                        read(rs.getString(7))),
                context.tenantId(), context.projectId(), riskRef);
        if (rows.isEmpty()) throw new ApiException("RISK_NOT_FOUND", 404, "risk not found");
        return rows.get(0);
    }

    private List<NodeRow> nodes(RequestContext context, String taskId, int revision) {
        return jdbc.query(
                "select node_id,node_type,payload::text from graph_nodes where tenant_id=? and project_id=? and task_id=? and created_revision<=? order by id",
                (rs, row) -> new NodeRow(rs.getString(1), rs.getString(2), read(rs.getString(3))),
                context.tenantId(), context.projectId(), taskId, revision);
    }

    private Map<String, List<Relation>> relations(RequestContext context, String taskId,
                                                   int revision) {
        Map<String, List<Relation>> result = new HashMap<>();
        jdbc.query(
                "select evidence_id,hypothesis_id,relation from evidence_edges where tenant_id=? and project_id=? and task_id=? and created_revision<=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                                .add(new Relation(rs.getString(2), rs.getString(3))),
                context.tenantId(), context.projectId(), taskId, revision);
        return result;
    }

    private void recordMetrics(BuildResult built, String role, boolean reused, long started) {
        String safeRole = role.toLowerCase(Locale.ROOT);
        metrics.counter("sitepilot.context.build.total", "role", safeRole).increment();
        if (reused) metrics.counter("sitepilot.context.snapshot.reuse.total", "role", safeRole).increment();
        metrics.summary("sitepilot.context.estimated.tokens", "role", safeRole)
                .record(built.estimatedTokens);
        metrics.summary("sitepilot.context.included.nodes", "role", safeRole)
                .record(built.pack.path("manifest").path("included_node_ids").size());
        JsonNode excluded = built.pack.path("manifest").path("excluded_counts");
        metrics.summary("sitepilot.context.excluded.nodes", "role", safeRole)
                .record(excluded.path("expired").asInt()
                        + excluded.path("irrelevant").asInt()
                        + excluded.path("budget_trimmed").asInt());
        metrics.timer("sitepilot.context.build.latency", "role", safeRole)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private Map<String, Object> response(ContextSnapshotEntity entity, boolean applied) {
        return Map.of(
                "contextId", entity.getContextId(),
                "contextDigest", entity.getContextDigest(),
                "graphRevision", entity.getGraphRevision(),
                "estimatedTokens", entity.getEstimatedTokens(),
                "contextPack", read(entity.getContextPayload()),
                "applied", applied);
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("stored JSON is invalid", exception); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("context cannot be serialized", exception); }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void require(boolean condition, String code, String detail) {
        if (!condition) throw new ApiException(code, 409, detail);
    }

    private record TaskRow(String taskId, String riskRef, String goal, int revision, long version) {}
    private record AgentTaskRow(String taskId, String role, String objective,
                                List<String> allowedTools, String verificationStage,
                                String capability) {}
    private record NodeRow(String id, String type, JsonNode payload) {}
    private record Relation(String hypothesisId, String relation) {}
    private record EventRisk(String riskId, String title, String status, String eventId,
                             String eventType, OffsetDateTime occurredAt, JsonNode attributes) {}
    private record BuildResult(String contextId, ObjectNode pack, String digest,
                               int estimatedTokens, String contextMode,
                               ContextSnapshotEntity parent, int chainDepth) {}
}
