package com.sitepilot.controlplane.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.error.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ConversationService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> create(CreateConversation request) {
        RequestContext context = RequestContextHolder.get();
        jdbc.update("insert into conversations (tenant_id,project_id,conversation_id,title,created_at,updated_at) values (?,?,?,?,?,?)",
                context.tenantId(), context.projectId(), request.conversationId(), request.title(),
                OffsetDateTime.now(), OffsetDateTime.now());
        return read(request.conversationId());
    }

    @Transactional
    public Map<String, Object> message(String conversationId, CreateMessage request) {
        RequestContext context = RequestContextHolder.get();
        ensure(context, conversationId);
        jdbc.update("insert into conversation_messages "
                        + "(tenant_id,project_id,conversation_id,message_id,message_role,content,citations,created_at) "
                        + "values (?,?,?,?,?,?,cast(? as jsonb),?)",
                context.tenantId(), context.projectId(), conversationId, request.messageId(),
                request.role(), request.content(), write(request.citations()), OffsetDateTime.now());
        jdbc.update("update conversations set updated_at=?,version=version+1 where tenant_id=? and project_id=? and conversation_id=?",
                OffsetDateTime.now(), context.tenantId(), context.projectId(), conversationId);
        return read(conversationId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> read(String conversationId) {
        RequestContext context = RequestContextHolder.get();
        List<Map<String, Object>> conversations = jdbc.queryForList(
                "select conversation_id,title,created_at,updated_at,version from conversations where tenant_id=? and project_id=? and conversation_id=?",
                context.tenantId(), context.projectId(), conversationId);
        if (conversations.isEmpty()) throw new ApiException("CONVERSATION_NOT_FOUND", 404, "conversation not found");
        List<Map<String, Object>> messages = jdbc.queryForList(
                "select message_id as \"messageId\",message_role as role,content,citations::text as citations,created_at as \"createdAt\" "
                        + "from conversation_messages where tenant_id=? and project_id=? and conversation_id=? order by created_at",
                context.tenantId(), context.projectId(), conversationId).stream().map(row -> {
                    row.put("citations", readJson(String.valueOf(row.get("citations"))));
                    return row;
                }).toList();
        Map<String, Object> row = conversations.get(0);
        return Map.of("conversationId", row.get("conversation_id"), "title", row.get("title"),
                "createdAt", row.get("created_at"), "updatedAt", row.get("updated_at"),
                "version", row.get("version"), "messages", messages);
    }

    private void ensure(RequestContext context, String conversationId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from conversations where tenant_id=? and project_id=? and conversation_id=?",
                Integer.class, context.tenantId(), context.projectId(), conversationId);
        if (count == null || count == 0) throw new ApiException("CONVERSATION_NOT_FOUND", 404, "conversation not found");
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private JsonNode readJson(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public record CreateConversation(String conversationId, String title) {}
    public record CreateMessage(String messageId, String role, String content, JsonNode citations) {}
}
