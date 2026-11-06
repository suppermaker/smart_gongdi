package com.sitepilot.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record InvestigationTask(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("task_id") String taskId,
        String goal,
        @JsonProperty("risk_ref") String riskRef,
        String status,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        OffsetDateTime deadline,
        @JsonProperty("graph_revision") int graphRevision,
        Budget budgets,
        @JsonProperty("pending_human_request_id") String pendingHumanRequestId) {

    public record Budget(
            @JsonProperty("max_model_calls") int maxModelCalls,
            @JsonProperty("max_tool_calls") int maxToolCalls,
            @JsonProperty("max_sub_agents") int maxSubAgents,
            @JsonProperty("max_graph_revisions") int maxGraphRevisions,
            @JsonProperty("timeout_seconds") int timeoutSeconds,
            @JsonProperty("max_cost_usd") Double maxCostUsd) {}
}
