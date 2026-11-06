package com.sitepilot.controlplane.context;

public record RequestContext(String tenantId, String projectId, String actor, String correlationId) {
    public RequestContext(String tenantId, String projectId, String actor) {
        this(tenantId, projectId, actor, "correlation-unspecified");
    }
}
