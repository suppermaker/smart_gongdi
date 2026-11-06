package com.sitepilot.controlplane.domain;

import com.sitepilot.controlplane.error.ApiException;
import java.util.Map;
import java.util.Set;

public enum RiskStatus {
    DETECTED,
    INVESTIGATING,
    AWAITING_HUMAN,
    AWAITING_APPROVAL,
    ACTING,
    VERIFYING,
    CLOSED,
    DISMISSED,
    FAILED;

    private static final Map<RiskStatus, Set<RiskStatus>> ALLOWED = Map.of(
            DETECTED, Set.of(INVESTIGATING, DISMISSED, FAILED),
            INVESTIGATING, Set.of(AWAITING_HUMAN, AWAITING_APPROVAL, FAILED, DISMISSED),
            AWAITING_HUMAN, Set.of(INVESTIGATING, FAILED),
            AWAITING_APPROVAL, Set.of(ACTING, INVESTIGATING, DISMISSED),
            ACTING, Set.of(VERIFYING, FAILED),
            VERIFYING, Set.of(CLOSED, INVESTIGATING, FAILED));

    public void requireTransitionTo(RiskStatus target) {
        if (!ALLOWED.getOrDefault(this, Set.of()).contains(target)) {
            throw new ApiException(
                    "RISK_TRANSITION_NOT_ALLOWED", 409,
                    "transition from " + this + " to " + target + " is not allowed");
        }
    }
}
