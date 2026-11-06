package com.sitepilot.controlplane.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.domain.AuditLogEntity;
import com.sitepilot.controlplane.repository.AuditLogRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpAuditService {
    private final AuditLogRepository audits;
    private final ObjectMapper json;

    public McpAuditService(AuditLogRepository audits, ObjectMapper json) {
        this.audits = audits;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(McpPrincipal principal, String tool, String status, String argumentsDigest) {
        audits.save(new AuditLogEntity(principal.tenantId(), principal.projectId(), principal.actorId(),
                "MCP_TOOL_CALL", "investigation_task", principal.taskId(), write(Map.of(
                        "taskId", principal.taskId(), "role", principal.role(), "tool", tool,
                        "status", status, "argumentsDigest", argumentsDigest))));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("audit details cannot be serialized", exception);
        }
    }
}
