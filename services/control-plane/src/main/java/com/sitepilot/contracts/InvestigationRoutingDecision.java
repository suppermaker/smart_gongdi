package com.sitepilot.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Cross-language routing decision. Identity and namespace are injected by the server. */
public record InvestigationRoutingDecision(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("decision_id") String decisionId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("graph_revision") int graphRevision,
        @JsonProperty("policy_version") String policyVersion,
        String provider,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("input_digest") String inputDigest,
        @JsonProperty("primary_intent") String primaryIntent,
        @JsonProperty("primary_confidence") double primaryConfidence,
        @JsonProperty("intent_probabilities") Map<String, Double> intentProbabilities,
        List<CapabilityDecision> capabilities,
        @JsonProperty("human_probability") double humanProbability,
        @JsonProperty("unknown_probability") double unknownProbability,
        @JsonProperty("urgency_score") double urgencyScore,
        @JsonProperty("route_disposition") String routeDisposition,
        @JsonProperty("reason_codes") List<String> reasonCodes,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public record CapabilityDecision(
            String capability,
            double probability,
            String disposition,
            @JsonProperty("supporting_fact_refs") List<String> supportingFactRefs) {}
}
