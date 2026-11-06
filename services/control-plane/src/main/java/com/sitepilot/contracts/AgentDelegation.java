package com.sitepilot.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AgentDelegation(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("delegation_id") String delegationId,
        @JsonProperty("task_id") String taskId,
        String role,
        String objective,
        List<String> dependencies,
        @JsonProperty("allowed_tools") List<String> allowedTools,
        @JsonProperty("input_graph_revision") int inputGraphRevision,
        InvestigationTask.Budget budgets,
        @JsonProperty("acceptance_criteria") List<String> acceptanceCriteria) {}
