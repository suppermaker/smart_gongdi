package com.sitepilot.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public final class Requests {
    private Requests() {}

    public record CreateProject(@NotBlank String name) {}

    public record IngestEvent(
            @NotBlank String eventId,
            @NotBlank String eventType,
            @NotBlank String title,
            @NotNull OffsetDateTime occurredAt,
            @NotNull JsonNode attributes) {}

    public record TransitionRisk(@NotBlank String status, @NotBlank String idempotencyKey) {}

    public record RegisterEvidence(
            @NotBlank String evidenceId,
            @NotBlank String riskRef,
            @NotBlank String sourceType,
            @NotBlank String sourceRef,
            String sourceVersion,
            @NotBlank String claim,
            @NotNull OffsetDateTime observedAt,
            OffsetDateTime validUntil,
            String digest,
            String quote,
            JsonNode structuredValue,
            @NotBlank String reliability,
            @NotBlank String completeness,
            @NotNull JsonNode toolProvenance) {}

    public record CreateTask(
            @NotBlank String taskId,
            @NotBlank String goal,
            @NotBlank String riskRef,
            @NotBlank String status,
            @Min(0) int graphRevision,
            @NotNull JsonNode budgets) {}
}
