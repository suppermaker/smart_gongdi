package com.sitepilot.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Non-authoritative, revision-scoped model input assembled from authoritative records. */
public record ContextPack(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("context_id") String contextId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("agent_task_id") String agentTaskId,
        @JsonProperty("graph_revision") int graphRevision,
        String role,
        @JsonProperty("verification_stage") String verificationStage,
        String objective,
        @JsonProperty("event_summary") JsonNode eventSummary,
        @JsonProperty("risk_summary") JsonNode riskSummary,
        @JsonProperty("active_hypotheses") List<JsonNode> activeHypotheses,
        @JsonProperty("evidence_cards") List<JsonNode> evidenceCards,
        @JsonProperty("open_conflicts") List<JsonNode> openConflicts,
        @JsonProperty("unresolved_questions") List<JsonNode> unresolvedQuestions,
        @JsonProperty("action_context") List<JsonNode> actionContext,
        @JsonProperty("allowed_capabilities") List<String> allowedCapabilities,
        @JsonProperty("allowed_tools") List<String> allowedTools,
        Map<String, Object> budget,
        ContextManifest manifest) {

    public record ContextManifest(
            @JsonProperty("context_policy_version") String contextPolicyVersion,
            @JsonProperty("prompt_version") String promptVersion,
            @JsonProperty("included_node_ids") List<String> includedNodeIds,
            @JsonProperty("excluded_counts") Map<String, Integer> excludedCounts,
            @JsonProperty("estimated_tokens") int estimatedTokens,
            @JsonProperty("context_digest") String contextDigest) {}
}
