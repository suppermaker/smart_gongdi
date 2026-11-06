package com.sitepilot.controlplane.mcp;

import java.util.Set;

public record McpPrincipal(
        String tenantId, String projectId, String actorId, String taskId,
        String role, Set<String> permissions, long expiresAt) {
}
