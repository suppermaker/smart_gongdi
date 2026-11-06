package com.sitepilot.controlplane.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class PlatformContracts {
    private PlatformContracts() {}

    public record CreatePlatformTask(
            @NotBlank String taskId,
            String conversationId,
            @NotBlank String userInput,
            @NotBlank String requestedOutput,
            @NotNull List<String> attachmentRefs,
            @NotBlank String idempotencyKey) {}

    public record PlatformTaskResponse(
            String taskId,
            String conversationId,
            String intent,
            String userInput,
            String requestedOutput,
            JsonNode attachmentRefs,
            JsonNode plan,
            String status,
            JsonNode result,
            long version) {}
}
