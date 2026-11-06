package com.sitepilot.controlplane.readmodel;

import com.sitepilot.controlplane.action.ActionService;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.error.ApiException;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/read")
public class ReadModelController {
    private final JdbcTemplate jdbc;
    private final ActionService actions;
    private final ScheduledExecutorService streams = Executors.newScheduledThreadPool(2);

    public ReadModelController(JdbcTemplate jdbc, ActionService actions) {
        this.jdbc = jdbc;
        this.actions = actions;
    }

    @GetMapping("/risks")
    List<Map<String, Object>> risks() {
        RequestContext c = RequestContextHolder.get();
        return jdbc.queryForList("select risk_id as \"riskId\",title,status,created_at as \"createdAt\",updated_at as \"updatedAt\",version from risks where tenant_id=? and project_id=? order by created_at desc",
                c.tenantId(), c.projectId());
    }

    @GetMapping("/risks/{riskId}")
    Map<String, Object> risk(@PathVariable String riskId) {
        RequestContext c = RequestContextHolder.get();
        return one("select risk_id as \"riskId\",title,status,source_event_id as \"sourceEventId\",created_at as \"createdAt\",updated_at as \"updatedAt\",version from risks where tenant_id=? and project_id=? and risk_id=?",
                "RISK_NOT_FOUND", c.tenantId(), c.projectId(), riskId);
    }

    @GetMapping("/risks/{riskId}/task-graph")
    Map<String, Object> graph(@PathVariable String riskId) {
        RequestContext c = RequestContextHolder.get();
        List<Map<String, Object>> tasks = jdbc.queryForList("select task_id as \"taskId\",goal,status,graph_revision as \"graphRevision\",version from investigation_tasks where tenant_id=? and project_id=? and risk_ref=? order by id",
                c.tenantId(), c.projectId(), riskId);
        List<Map<String, Object>> nodes = jdbc.queryForList("select n.task_id as \"taskId\",n.node_id as \"nodeId\",n.node_type as \"nodeType\",n.payload::text as payload,n.created_revision as \"createdRevision\" from graph_nodes n join investigation_tasks t on t.tenant_id=n.tenant_id and t.project_id=n.project_id and t.task_id=n.task_id where n.tenant_id=? and n.project_id=? and t.risk_ref=? order by n.id",
                c.tenantId(), c.projectId(), riskId);
        List<Map<String, Object>> edges = jdbc.queryForList("select e.task_id as \"taskId\",e.evidence_id as \"evidenceId\",e.hypothesis_id as \"hypothesisId\",e.relation,e.created_revision as \"createdRevision\" from evidence_edges e join investigation_tasks t on t.tenant_id=e.tenant_id and t.project_id=e.project_id and t.task_id=e.task_id where e.tenant_id=? and e.project_id=? and t.risk_ref=? order by e.id",
                c.tenantId(), c.projectId(), riskId);
        List<Map<String, Object>> revisions = jdbc.queryForList("select r.task_id as \"taskId\",r.revision,r.parent_revision as \"parentRevision\",r.delta_id as \"deltaId\",r.created_at as \"createdAt\" from graph_revisions r join investigation_tasks t on t.tenant_id=r.tenant_id and t.project_id=r.project_id and t.task_id=r.task_id where r.tenant_id=? and r.project_id=? and t.risk_ref=? order by r.id",
                c.tenantId(), c.projectId(), riskId);
        List<Map<String, Object>> actionRows = jdbc.queryForList("select a.action_id as \"actionId\",a.task_id as \"taskId\",a.action_type as \"actionType\",a.level,a.status,a.version from action_proposals a join investigation_tasks t on t.tenant_id=a.tenant_id and t.project_id=a.project_id and t.task_id=a.task_id where a.tenant_id=? and a.project_id=? and t.risk_ref=? order by a.id",
                c.tenantId(), c.projectId(), riskId);
        return Map.of("tasks", tasks, "nodes", nodes, "edges", edges, "revisions", revisions,
                "conflicts", nodes.stream().filter(n -> "CONFLICT".equals(n.get("nodeType"))).toList(),
                "actions", actionRows);
    }

    @GetMapping("/risks/{riskId}/workflow-status")
    Map<String, Object> workflow(@PathVariable String riskId) {
        RequestContext c = RequestContextHolder.get();
        return Map.of("tasks", jdbc.queryForList("select task_id as \"taskId\",status,graph_revision as \"graphRevision\",version from investigation_tasks where tenant_id=? and project_id=? and risk_ref=? order by id", c.tenantId(), c.projectId(), riskId),
                "actions", jdbc.queryForList("select a.action_id as \"actionId\",a.status,a.level,a.version from action_proposals a join investigation_tasks t on t.tenant_id=a.tenant_id and t.project_id=a.project_id and t.task_id=a.task_id where a.tenant_id=? and a.project_id=? and t.risk_ref=? order by a.id", c.tenantId(), c.projectId(), riskId));
    }

    @GetMapping("/pending")
    Map<String, Object> pending() {
        RequestContext c = RequestContextHolder.get();
        List<Map<String, Object>> questions = jdbc.queryForList("select task_id as \"taskId\",node_id as \"questionId\",payload::text as payload from graph_nodes where tenant_id=? and project_id=? and node_type='QUESTION' order by id", c.tenantId(), c.projectId());
        List<Map<String, Object>> approvals = jdbc.queryForList("select action_id as \"actionId\",task_id as \"taskId\",action_type as \"actionType\",level,status,version from action_proposals where tenant_id=? and project_id=? and status='WAITING_APPROVAL' order by id", c.tenantId(), c.projectId());
        return Map.of("questions", questions, "approvals", approvals);
    }

    @GetMapping("/risks/{riskId}/audit-timeline")
    List<Map<String, Object>> timeline(@PathVariable String riskId) {
        RequestContext c = RequestContextHolder.get();
        return jdbc.queryForList("select id,actor,action,aggregate_type as \"aggregateType\",aggregate_id as \"aggregateId\",details::text as details,created_at as \"createdAt\" from audit_logs where tenant_id=? and project_id=? and (aggregate_id=? or aggregate_id in (select task_id from investigation_tasks where tenant_id=? and project_id=? and risk_ref=?)) order by id",
                c.tenantId(), c.projectId(), riskId, c.tenantId(), c.projectId(), riskId);
    }

    @GetMapping("/risks/{riskId}/final-result")
    Map<String, Object> finalResult(@PathVariable String riskId) {
        RequestContext c = RequestContextHolder.get();
        List<Map<String, Object>> rows = jdbc.queryForList("select a.action_id as \"actionId\",a.status,a.observation::text as observation,a.closed_at as \"closedAt\" from action_proposals a join investigation_tasks t on t.tenant_id=a.tenant_id and t.project_id=a.project_id and t.task_id=a.task_id where a.tenant_id=? and a.project_id=? and t.risk_ref=? and a.status in ('CLOSED','REOPENED') order by a.updated_at desc",
                c.tenantId(), c.projectId(), riskId);
        return Map.of("riskId", riskId, "results", rows);
    }

    @PostMapping("/actions/{actionId}/approval")
    Map<String, Object> approve(@PathVariable String actionId, @Valid @RequestBody ApprovalRequest request) {
        return actions.approve(RequestContextHolder.get(), actionId, request.expectedVersion(), request.idempotencyKey());
    }

    @GetMapping("/stream")
    SseEmitter stream(@RequestParam String tenant, @RequestParam String project,
                      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        RequestContext c = RequestContextHolder.get();
        NamespaceParameters namespace = new NamespaceParameters(tenant, project);
        if (!namespace.matches(c)) {
            throw new ApiException("NAMESPACE_MISMATCH", 403, "stream namespace does not match trusted context");
        }
        long after = parseLastEventId(lastEventId);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        var future = streams.scheduleAtFixedRate(new StreamPoller(jdbc, emitter, namespace, after), 0, 5, TimeUnit.SECONDS);
        emitter.onCompletion(() -> future.cancel(false));
        emitter.onTimeout(() -> future.cancel(false));
        emitter.onError(error -> future.cancel(false));
        return emitter;
    }

    private Map<String, Object> one(String sql, String code, Object... arguments) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, arguments);
        if (rows.isEmpty()) throw new ApiException(code, 404, "read model not found");
        return rows.get(0);
    }

    private long parseLastEventId(String value) {
        try { return value == null ? 0 : Long.parseLong(value); }
        catch (NumberFormatException exception) { throw new ApiException("LAST_EVENT_ID_INVALID", 400, "Last-Event-ID must be numeric"); }
    }

    @PreDestroy
    void stop() { streams.shutdownNow(); }

    public record NamespaceParameters(String tenantId, String projectId) {
        boolean matches(RequestContext c) { return tenantId.equals(c.tenantId()) && projectId.equals(c.projectId()); }
    }
    public record ApprovalRequest(@Min(0) long expectedVersion, @NotBlank String idempotencyKey) {}

    private static final class StreamPoller implements Runnable {
        private final JdbcTemplate jdbc;
        private final SseEmitter emitter;
        private final NamespaceParameters namespace;
        private long after;
        private StreamPoller(JdbcTemplate jdbc, SseEmitter emitter, NamespaceParameters namespace, long after) {
            this.jdbc = jdbc; this.emitter = emitter; this.namespace = namespace; this.after = after;
        }
        @Override public void run() {
            try {
                List<Map<String, Object>> events = jdbc.queryForList("select id,event_type as \"eventType\",aggregate_type as \"aggregateType\",aggregate_id as \"aggregateId\",payload::text as payload,created_at as \"createdAt\" from outbox_events where tenant_id=? and project_id=? and id>? order by id limit 100", namespace.tenantId(), namespace.projectId(), after);
                for (Map<String, Object> event : events) {
                    after = ((Number) event.get("id")).longValue();
                    emitter.send(SseEmitter.event().id(Long.toString(after)).name("state").data(event));
                }
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException exception) {
                emitter.completeWithError(exception);
            } catch (RuntimeException exception) {
                emitter.completeWithError(exception);
            }
        }
    }
}
