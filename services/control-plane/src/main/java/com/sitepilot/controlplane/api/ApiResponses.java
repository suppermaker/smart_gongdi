package com.sitepilot.controlplane.api;

import com.sitepilot.controlplane.domain.ProjectEntity;
import java.time.OffsetDateTime;

public final class ApiResponses {
    private ApiResponses() {}

    public record ProjectResponse(String name, OffsetDateTime createdAt, long version) {
        public static ProjectResponse from(ProjectEntity project) {
            return new ProjectResponse(project.getName(), project.getCreatedAt(), project.getVersion());
        }
    }
}
