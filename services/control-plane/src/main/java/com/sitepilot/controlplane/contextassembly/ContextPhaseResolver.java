package com.sitepilot.controlplane.contextassembly;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ContextPhaseResolver {
    public ContextPhase resolve(String role, String verificationStage,
                                List<JsonNode> conflicts, List<JsonNode> actions) {
        boolean criticalConflict = conflicts.stream().anyMatch(item ->
                "critical".equalsIgnoreCase(item.path("severity").asText()));
        if (criticalConflict) return ContextPhase.CONFLICT_RESOLUTION;
        if ("closure".equalsIgnoreCase(verificationStage)
                || actions.stream().anyMatch(this::executed)) return ContextPhase.CLOSURE;
        if ("verifier".equalsIgnoreCase(role)
                && "conclusion".equalsIgnoreCase(verificationStage)) return ContextPhase.CONCLUSION;
        return ContextPhase.EXPLORATION;
    }

    private boolean executed(JsonNode action) {
        String status = action.path("status").asText();
        return "EXECUTED".equalsIgnoreCase(status)
                || "OBSERVED".equalsIgnoreCase(status)
                || "VERIFIED".equalsIgnoreCase(status);
    }

    public enum ContextPhase {
        EXPLORATION,
        CONFLICT_RESOLUTION,
        CONCLUSION,
        CLOSURE
    }
}
