package com.sitepilot.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Typed cross-language graph delta. Namespace and identity are intentionally absent. */
public record GraphDelta(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("delta_id") String deltaId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("agent_task_id") String agentTaskId,
        @JsonProperty("base_revision") int baseRevision,
        @JsonProperty("new_hypotheses") List<JsonNode> newHypotheses,
        @JsonProperty("new_evidence") List<JsonNode> newEvidence,
        @JsonProperty("new_questions") List<JsonNode> newQuestions,
        @JsonProperty("new_conflicts") List<JsonNode> newConflicts,
        @JsonProperty("action_proposals") List<JsonNode> actionProposals,
        @JsonProperty("hypothesis_updates") List<JsonNode> hypothesisUpdates,
        JsonNode usage,
        String explanation) {
    public GraphDelta {
        newHypotheses = safe(newHypotheses);
        newEvidence = safe(newEvidence);
        newQuestions = safe(newQuestions);
        newConflicts = safe(newConflicts);
        actionProposals = safe(actionProposals);
        hypothesisUpdates = safe(hypothesisUpdates);
    }
    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : List.copyOf(value); }
}
