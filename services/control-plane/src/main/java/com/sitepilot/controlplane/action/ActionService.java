package com.sitepilot.controlplane.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.domain.AuditLogEntity;
import com.sitepilot.controlplane.domain.OutboxEventEntity;
import com.sitepilot.controlplane.error.ApiException;
import com.sitepilot.controlplane.repository.AuditLogRepository;
import com.sitepilot.controlplane.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;

    public ActionService(JdbcTemplate jdbc, ObjectMapper json, AuditLogRepository audits, OutboxEventRepository outbox) {
        this.jdbc = jdbc; this.json = json; this.audits = audits; this.outbox = outbox;
    }

    @Transactional
    public Map<String, Object> propose(RequestContext c, JsonNode proposal) {
        String actionId = required(proposal, "action_id");
        String taskId = required(proposal, "task_id");
        String level = required(proposal, "level");
        if ("L3".equals(level)) throw conflict("ACTION_LEVEL_FORBIDDEN", "L3 actions are forbidden to agents");
        if (!List.of("L0", "L1", "L2").contains(level)) throw conflict("ACTION_LEVEL_INVALID", "invalid action level");
        String idempotency = required(proposal, "idempotency_key");
        requireTask(c, taskId);
        String payload = write(proposal);
        String semanticHash = hash(payload);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select action_id,semantic_hash from action_proposals where tenant_id=? and project_id=? and task_id=? and idempotency_key=?",
                c.tenantId(), c.projectId(), taskId, idempotency);
        if (!existing.isEmpty()) {
            if (!semanticHash.equals(existing.get(0).get("semantic_hash"))) throw conflict("ACTION_IDEMPOTENCY_CONFLICT", "idempotency key has different semantics");
            return read(c, String.valueOf(existing.get(0).get("action_id")));
        }
        String status = "L2".equals(level) ? "WAITING_APPROVAL" : "VALIDATED";
        int window = proposal.path("verification_window_seconds").asInt(0);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("insert into action_proposals (tenant_id,project_id,action_id,task_id,action_type,level,status,reason,evidence_refs,verification_criteria,verification_window_seconds,idempotency_key,semantic_hash,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,0,?,?)",
                c.tenantId(), c.projectId(), actionId, taskId, required(proposal, "action_type"), level, status,
                required(proposal, "reason"), write(proposal.path("evidence_refs")),
                write(proposal.path("verification_criteria")), window, idempotency, semanticHash, now, now);
        event(c, actionId, "action.proposed", Map.of("taskId", taskId, "level", level, "status", status));
        audit(c, actionId, "ACTION_PROPOSED", Map.of("taskId", taskId, "level", level));
        return read(c, actionId);
    }

    @Transactional
    public Map<String, Object> approve(RequestContext c, String actionId, long expectedVersion) {
        return approve(c, actionId, expectedVersion, "legacy:" + expectedVersion);
    }

    @Transactional
    public Map<String, Object> approve(RequestContext c, String actionId, long expectedVersion, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ApiException("APPROVAL_IDEMPOTENCY_REQUIRED", 400, "idempotency key is required");
        ActionRow row = lock(c, actionId);
        List<Map<String, Object>> prior = jdbc.queryForList("select approval_idempotency_key from action_proposals where tenant_id=? and project_id=? and action_id=?", c.tenantId(), c.projectId(), actionId);
        Object recorded = prior.get(0).get("approval_idempotency_key");
        if (recorded != null) {
            if (!idempotencyKey.equals(recorded.toString())) throw conflict("APPROVAL_IDEMPOTENCY_CONFLICT", "approval already used another idempotency key");
            return read(c, actionId);
        }
        if (row.version != expectedVersion) throw conflict("ACTION_VERSION_CONFLICT", "action version is stale");
        if (!"L2".equals(row.level)) throw conflict("ACTION_APPROVAL_NOT_REQUIRED", "only L2 actions require approval");
        if ("APPROVED".equals(row.status) || "EXECUTED".equals(row.status)) return read(c, actionId);
        if (!"WAITING_APPROVAL".equals(row.status)) throw conflict("ACTION_STATE_INVALID", "action is not waiting for approval");
        update(c, actionId, expectedVersion, "APPROVED", "approved_by=?, approved_at=?, approval_idempotency_key=?", c.actor(), OffsetDateTime.now(), idempotencyKey);
        event(c, actionId, "action.approved", Map.of("approvedBy", c.actor()));
        audit(c, actionId, "ACTION_APPROVED", Map.of("approvedBy", c.actor()));
        return read(c, actionId);
    }

    @Transactional
    public Map<String, Object> execute(RequestContext c, String actionId, long expectedVersion) {
        ActionRow row = lock(c, actionId);
        if (row.version != expectedVersion) throw conflict("ACTION_VERSION_CONFLICT", "action version is stale");
        if ("EXECUTED".equals(row.status) || "OBSERVING".equals(row.status) || "CLOSED".equals(row.status) || "REOPENED".equals(row.status)) return read(c, actionId);
        if ("L2".equals(row.level) && !"APPROVED".equals(row.status)) throw conflict("ACTION_APPROVAL_REQUIRED", "high-risk action requires human approval");
        if (!List.of("VALIDATED", "APPROVED").contains(row.status)) throw conflict("ACTION_STATE_INVALID", "action cannot execute");
        update(c, actionId, expectedVersion, "EXECUTED", "executed_at=?", OffsetDateTime.now());
        event(c, actionId, "action.executed", Map.of("taskId", row.taskId));
        audit(c, actionId, "ACTION_EXECUTED", Map.of("taskId", row.taskId));
        return read(c, actionId);
    }

    @Transactional
    public Map<String, Object> observeAndVerify(RequestContext c, String actionId, long expectedVersion, JsonNode observation) {
        ActionRow row = lock(c, actionId);
        if (row.version != expectedVersion) throw conflict("ACTION_VERSION_CONFLICT", "action version is stale");
        if (!"EXECUTED".equals(row.status) && !"REOPENED".equals(row.status)) throw conflict("ACTION_STATE_INVALID", "action has not executed");
        boolean changed = observation.path("environment_changed").asBoolean(false);
        boolean verifier = observation.path("closure_verifier_approved").asBoolean(false);
        String evidenceId = required(observation, "current_evidence_id");
        List<Map<String, Object>> evidence = jdbc.queryForList(
                "select payload from graph_nodes where tenant_id=? and project_id=? and task_id=? and node_type='EVIDENCE' and node_id=?",
                c.tenantId(), c.projectId(), row.taskId, evidenceId);
        boolean current = false;
        if (!evidence.isEmpty()) {
            JsonNode item = readJson(evidence.get(0).get("payload"));
            OffsetDateTime observed = OffsetDateTime.parse(item.path("observed_at").asText());
            OffsetDateTime validUntil = OffsetDateTime.parse(item.path("valid_until").asText());
            current = row.executedAt != null && observed.isAfter(row.executedAt) && validUntil.isAfter(OffsetDateTime.now());
        }
        String status = changed && verifier && current ? "CLOSED" : "REOPENED";
        update(c, actionId, expectedVersion, status, "observation=cast(? as jsonb), closed_at=?",
                write(observation), "CLOSED".equals(status) ? OffsetDateTime.now() : null);
        event(c, actionId, "CLOSED".equals(status) ? "action.closed" : "action.reopened",
                Map.of("currentEvidence", current, "environmentChanged", changed, "verifierApproved", verifier));
        audit(c, actionId, "CLOSED".equals(status) ? "ACTION_CLOSED" : "ACTION_REOPENED",
                Map.of("currentEvidence", current, "environmentChanged", changed));
        return read(c, actionId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> read(RequestContext c, String actionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select action_id as \"actionId\",task_id as \"taskId\",action_type as \"actionType\",level,status,version,approved_by as \"approvedBy\",approved_at as \"approvedAt\",executed_at as \"executedAt\",observation from action_proposals where tenant_id=? and project_id=? and action_id=?",
                c.tenantId(), c.projectId(), actionId);
        if (rows.isEmpty()) throw new ApiException("ACTION_NOT_FOUND", 404, "action not found");
        return rows.get(0);
    }

    private void update(RequestContext c, String actionId, long expectedVersion, String status, String fields, Object... values) {
        Object[] args = new Object[values.length + 6];
        args[0] = status;
        System.arraycopy(values, 0, args, 1, values.length);
        int p = values.length + 1;
        args[p++] = OffsetDateTime.now(); args[p++] = c.tenantId(); args[p++] = c.projectId(); args[p++] = actionId; args[p] = expectedVersion;
        int updated = jdbc.update("update action_proposals set status=?," + fields + ",updated_at=?,version=version+1 where tenant_id=? and project_id=? and action_id=? and version=?", args);
        if (updated != 1) throw conflict("ACTION_VERSION_CONFLICT", "action changed concurrently");
    }

    private ActionRow lock(RequestContext c, String actionId) {
        List<ActionRow> rows = jdbc.query("select task_id,level,status,version,executed_at from action_proposals where tenant_id=? and project_id=? and action_id=? for update",
                (rs, n) -> new ActionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getObject(5, OffsetDateTime.class)),
                c.tenantId(), c.projectId(), actionId);
        if (rows.isEmpty()) throw new ApiException("ACTION_NOT_FOUND", 404, "action not found");
        return rows.get(0);
    }
    private void requireTask(RequestContext c, String taskId) {
        Integer count = jdbc.queryForObject("select count(*) from investigation_tasks where tenant_id=? and project_id=? and task_id=?", Integer.class, c.tenantId(), c.projectId(), taskId);
        if (count == null || count == 0) throw new ApiException("TASK_NOT_FOUND", 404, "task not found");
    }
    private void event(RequestContext c, String actionId, String type, Object payload) {
        outbox.save(new OutboxEventEntity(c.tenantId(), c.projectId(), type + ":" + actionId,
                "action", actionId, type, write(payload)));
    }
    private void audit(RequestContext c, String actionId, String action, Object detail) {
        audits.save(new AuditLogEntity(c.tenantId(), c.projectId(), c.actor(), action, "action", actionId, write(detail)));
    }
    private String required(JsonNode node, String name) {
        String value = node.path(name).asText();
        if (value.isBlank()) throw new ApiException("ACTION_CONTRACT_INVALID", 400, name + " is required");
        return value;
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    private JsonNode readJson(Object value) { try { return json.readTree(String.valueOf(value)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private ApiException conflict(String code, String message) { return new ApiException(code, 409, message); }
    private record ActionRow(String taskId, String level, String status, long version, OffsetDateTime executedAt) {}
}
