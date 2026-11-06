package com.sitepilot.controlplane.contextassembly;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ContextSlicePolicy {
    public boolean include(String role, String capability, JsonNode evidence,
                           List<String> relations, ContextPhaseResolver.ContextPhase phase,
                           boolean mandatory) {
        if (mandatory || relations.contains("CONTRADICTS")) return true;
        String content = evidence.toString().toLowerCase(Locale.ROOT);
        if (phase == ContextPhaseResolver.ContextPhase.CLOSURE
                && containsAny(content, "current", "observation", "remediation", "post_action")) {
            return true;
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "supervisor" -> true;
            case "verifier" -> !relations.isEmpty()
                    || containsAny(content, "verification", "current", "conflict", "closure");
            case "compliance" -> containsAny(content, "policy", "regulation", "permit",
                    "certificate", "requirement", "compliance");
            case "investigator" -> !relations.isEmpty() || capabilityRelevant(content, capability);
            default -> false;
        };
    }

    public String primaryReason(JsonNode evidence, List<String> relations,
                                ContextPhaseResolver.ContextPhase phase,
                                boolean criticalConflict, boolean mandatory) {
        if (criticalConflict) return "CRITICAL_CONFLICT";
        if (relations.contains("CONTRADICTS")) return "COUNTEREVIDENCE";
        String content = evidence.toString().toLowerCase(Locale.ROOT);
        if (phase == ContextPhaseResolver.ContextPhase.CLOSURE
                && containsAny(content, "current", "observation", "remediation", "post_action")) {
            return "CLOSURE_PREREQUISITE";
        }
        return mandatory ? "REQUIRED_EVIDENCE" : "ROLE_RELEVANT";
    }

    private boolean capabilityRelevant(String content, String capability) {
        for (String term : capability.toLowerCase(Locale.ROOT).split("_")) {
            if (term.length() > 3 && content.contains(term)) return true;
        }
        return false;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
