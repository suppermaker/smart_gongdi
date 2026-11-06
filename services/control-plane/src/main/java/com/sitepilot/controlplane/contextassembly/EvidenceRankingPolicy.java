package com.sitepilot.controlplane.contextassembly;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EvidenceRankingPolicy {
    public double score(JsonNode evidence, String capability, int graphDistance) {
        double relevance = relevance(evidence, capability);
        double information = informationValue(evidence);
        double reliability = reliability(evidence.path("reliability").asText());
        double freshness = freshness(evidence);
        double proximity = graphDistance <= 1 ? 1.0 : graphDistance == 2 ? 0.5 : 0.1;
        return clamp(0.30 * relevance + 0.25 * information + 0.20 * reliability
                + 0.15 * freshness + 0.10 * proximity);
    }

    public String freshnessLabel(JsonNode evidence) {
        if (!evidence.hasNonNull("valid_until")) return "NEAR_EXPIRY";
        OffsetDateTime validUntil = OffsetDateTime.parse(evidence.path("valid_until").asText());
        if (validUntil.isBefore(OffsetDateTime.now())) return "EXPIRED";
        return validUntil.isBefore(OffsetDateTime.now().plusMinutes(5)) ? "NEAR_EXPIRY" : "CURRENT";
    }

    private double relevance(JsonNode evidence, String capability) {
        String content = evidence.toString().toLowerCase(Locale.ROOT);
        Set<String> terms = switch (capability) {
            case "network_diagnosis" -> Set.of("network", "gateway", "uplink", "collector", "nearby");
            case "equipment_observation", "equipment_diagnosis" -> Set.of("equipment", "device", "crane", "status");
            case "identity_resolution", "certificate_verification", "permit_validation" -> Set.of("identity", "worker", "certificate", "permit");
            case "regulation_retrieval" -> Set.of("policy", "regulation", "permit", "requirement");
            case "evidence_validation", "remediation_validation", "current_observation" -> Set.of("evidence", "remediation", "observation", "current", "submission");
            default -> Set.of();
        };
        return terms.stream().anyMatch(content::contains) ? 1.0 : 0.35;
    }

    private double informationValue(JsonNode evidence) {
        int relationCount = evidence.path("supports").size() + evidence.path("contradicts").size();
        return relationCount > 1 ? 1.0 : relationCount == 1 ? 0.75 : 0.35;
    }

    private double reliability(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "high", "authoritative" -> 1.0;
            case "medium" -> 0.7;
            case "low" -> 0.4;
            default -> 0.2;
        };
    }

    private double freshness(JsonNode evidence) {
        return switch (freshnessLabel(evidence)) {
            case "CURRENT" -> 1.0;
            case "NEAR_EXPIRY" -> 0.6;
            default -> 0.0;
        };
    }

    private double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
}
