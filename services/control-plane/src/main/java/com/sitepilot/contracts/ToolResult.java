package com.sitepilot.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;

public record ToolResult(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("tool_name") String toolName,
        String status,
        @JsonProperty("observed_at") OffsetDateTime observedAt,
        @JsonProperty("source_version") String sourceVersion,
        String completeness,
        Object payload,
        Map<String, Object> error) {}
