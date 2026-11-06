package com.sitepilot.controlplane.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformTaskService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlatformDagCompiler dags;

    public PlatformTaskService(JdbcTemplate jdbc, ObjectMapper json, PlatformDagCompiler dags) {
        this.jdbc = jdbc;
        this.json = json;
        this.dags = dags;
    }

    @Transactional
    public PlatformContracts.PlatformTaskResponse create(
            PlatformContracts.CreatePlatformTask request) {
        RequestContext context = RequestContextHolder.get();
        String intent = classify(request.userInput(), request.requestedOutput());
        String attachments = write(request.attachmentRefs());
        JsonNode plan = dags.compile(intent, request.taskId());
        String semantics = hash(write(Map.of(
                "input", request.userInput(),
                "output", request.requestedOutput(),
                "attachments", request.attachmentRefs())));
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select semantic_hash from platform_tasks where tenant_id=? and project_id=? and idempotency_key=?",
                context.tenantId(), context.projectId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            if (!semantics.equals(existing.get(0).get("semantic_hash"))) {
                throw new ApiException("PLATFORM_IDEMPOTENCY_CONFLICT", 409,
                        "idempotency key has different semantics");
            }
            return read(request.taskId());
        }
        try {
            jdbc.update("insert into platform_tasks "
                            + "(tenant_id,project_id,task_id,conversation_id,intent,user_input,"
                            + "requested_output,attachment_refs,plan,status,idempotency_key,"
                            + "semantic_hash,created_at,updated_at) "
                            + "values (?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,?,?)",
                    context.tenantId(), context.projectId(), request.taskId(),
                    request.conversationId(), intent, request.userInput(),
                    request.requestedOutput(), attachments, write(plan), "PLANNED",
                    request.idempotencyKey(), semantics, OffsetDateTime.now(), OffsetDateTime.now());
        } catch (DuplicateKeyException conflict) {
            throw new ApiException("PLATFORM_TASK_CONFLICT", 409, "platform task already exists");
        }
        return read(request.taskId());
    }

    @Transactional(readOnly = true)
    public PlatformContracts.PlatformTaskResponse read(String taskId) {
        RequestContext context = RequestContextHolder.get();
        List<PlatformContracts.PlatformTaskResponse> rows = jdbc.query(
                "select task_id,conversation_id,intent,user_input,requested_output,"
                        + "attachment_refs::text,plan::text,status,result::text,version "
                        + "from platform_tasks where tenant_id=? and project_id=? and task_id=?",
                (rs, row) -> new PlatformContracts.PlatformTaskResponse(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), readJson(rs.getString(6)), readJson(rs.getString(7)),
                        rs.getString(8), readNullable(rs.getString(9)), rs.getLong(10)),
                context.tenantId(), context.projectId(), taskId);
        if (rows.isEmpty()) throw new ApiException("PLATFORM_TASK_NOT_FOUND", 404, "task not found");
        return rows.get(0);
    }

    private String classify(String input, String output) {
        String text = input.toLowerCase(Locale.ROOT);
        String requested = output.toLowerCase(Locale.ROOT);
        if (requested.matches("report|docx|pdf") || contains(text, "报告", "月报", "周报")) return "REPORT_GENERATION";
        if (requested.equals("review") || contains(text, "审查", "合规", "修改建议")) return "DOCUMENT_REVIEW";
        if (requested.equals("ocr") || contains(text, "识别", "身份证", "车牌", "发票", "提取合同")) return "OCR_EXTRACTION";
        if (contains(text, "调查", "根因", "整改是否完成")) return "RISK_INVESTIGATION";
        boolean database = contains(text, "库存", "考勤", "本月", "数量", "进度", "成本", "数据库");
        boolean knowledge = contains(text, "规范", "制度", "条款", "文档", "要求");
        if (database && knowledge) return "HYBRID_QA";
        if (database) return "DATABASE_QA";
        if (knowledge) return "KNOWLEDGE_QA";
        return "HUMAN_CLARIFICATION";
    }

    private boolean contains(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private JsonNode readJson(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private JsonNode readNullable(String value) {
        return value == null ? null : readJson(value);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
