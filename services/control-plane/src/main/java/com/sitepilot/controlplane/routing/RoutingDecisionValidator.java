package com.sitepilot.controlplane.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitepilot.controlplane.error.ApiException;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RoutingDecisionValidator {
    private static final Set<String> CAPABILITIES = Set.of(
            "identity_resolution", "certificate_verification", "permit_validation",
            "equipment_observation", "equipment_diagnosis", "network_diagnosis",
            "maintenance_lookup", "evidence_validation", "regulation_retrieval",
            "remediation_validation", "current_observation", "plan_verification",
            "conclusion_verification", "closure_verification", "human_clarification");
    private static final Set<String> ROUTES = Set.of(
            "AUTO_ROUTE", "COLLECT_EVIDENCE", "HUMAN_CLARIFICATION", "HUMAN_ESCALATION");
    private static final Set<String> DISPOSITIONS = Set.of("REQUIRED", "OPTIONAL_IF_BUDGET", "SKIP");

    public void validate(JsonNode decision, String taskId, int graphRevision) {
        requiredText(decision, "schema_version");
        if (!"1.0".equals(decision.path("schema_version").asText())) fail("unsupported schema version");
        if (!taskId.equals(requiredText(decision, "task_id"))) fail("task id mismatch");
        if (decision.path("graph_revision").asInt(-1) != graphRevision) fail("graph revision mismatch");
        requiredText(decision, "decision_id");
        requiredText(decision, "policy_version");
        requiredText(decision, "provider");
        requiredText(decision, "model_version");
        String digest = requiredText(decision, "input_digest");
        if (!digest.matches("sha256:[a-f0-9]{64}")) fail("invalid input digest");
        probability(decision, "primary_confidence");
        probability(decision, "human_probability");
        probability(decision, "unknown_probability");
        double urgency = decision.path("urgency_score").asDouble(-1);
        if (urgency < 0 || urgency > 3) fail("invalid urgency score");
        if (!ROUTES.contains(requiredText(decision, "route_disposition"))) fail("invalid route disposition");
        if (!decision.path("intent_probabilities").isObject()) fail("intent probabilities required");
        decision.path("intent_probabilities").fields().forEachRemaining(entry -> probability(entry.getValue()));
        if (!decision.path("capabilities").isArray()) fail("capabilities required");
        for (JsonNode capability : decision.path("capabilities")) {
            if (!CAPABILITIES.contains(requiredText(capability, "capability"))) fail("unknown capability");
            probability(capability, "probability");
            if (!DISPOSITIONS.contains(requiredText(capability, "disposition"))) fail("invalid capability disposition");
            if (!capability.path("supporting_fact_refs").isArray()) fail("supporting fact refs required");
        }
    }

    private String requiredText(JsonNode value, String field) {
        String result = value.path(field).asText("");
        if (result.isBlank()) fail("missing " + field);
        return result;
    }

    private void probability(JsonNode value, String field) { probability(value.path(field)); }

    private void probability(JsonNode value) {
        if (!value.isNumber() || value.asDouble() < 0 || value.asDouble() > 1) fail("invalid probability");
    }

    private void fail(String detail) {
        throw new ApiException("ROUTING_DECISION_INVALID", 400, detail);
    }
}
