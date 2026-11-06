package com.sitepilot.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Version 2 context with lineage, graph frontier and progressive evidence handles. */
public record ContextPackV2(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("context_id") String contextId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("agent_task_id") String agentTaskId,
        @JsonProperty("graph_revision") int graphRevision,
        String role,
        @JsonProperty("verification_stage") String verificationStage,
        String objective,
        @JsonProperty("context_phase") String contextPhase,
        @JsonProperty("context_mode") String contextMode,
        @JsonProperty("parent_context") ParentContext parentContext,
        @JsonProperty("event_summary") JsonNode eventSummary,
        @JsonProperty("risk_summary") JsonNode riskSummary,
        @JsonProperty("active_hypotheses") List<JsonNode> activeHypotheses,
        @JsonProperty("evidence_cards") List<JsonNode> evidenceCards,
        @JsonProperty("evidence_handles") List<JsonNode> evidenceHandles,
        @JsonProperty("open_conflicts") List<JsonNode> openConflicts,
        @JsonProperty("unresolved_questions") List<JsonNode> unresolvedQuestions,
        @JsonProperty("action_context") List<JsonNode> actionContext,
        @JsonProperty("reasoning_frontier") JsonNode reasoningFrontier,
        JsonNode delta,
        @JsonProperty("allowed_capabilities") List<String> allowedCapabilities,
        @JsonProperty("allowed_tools") List<String> allowedTools,
        Map<String, Object> budget,
        ContextManifestV2 manifest) {

    public record ParentContext(
            @JsonProperty("context_id") String contextId,
            @JsonProperty("context_digest") String contextDigest,
            @JsonProperty("graph_revision") int graphRevision) {}

    public record ContextManifestV2(
            @JsonProperty("context_policy_version") String contextPolicyVersion,
            @JsonProperty("prompt_version") String promptVersion,
            @JsonProperty("included_node_ids") List<String> includedNodeIds,
            @JsonProperty("excluded_counts") Map<String, Integer> excludedCounts,
            @JsonProperty("selection_reasons") Map<String, List<String>> selectionReasons,
            @JsonProperty("token_allocation") Map<String, Integer> tokenAllocation,
            @JsonProperty("estimated_tokens") int estimatedTokens,
            @JsonProperty("context_digest") String contextDigest) {}
}
