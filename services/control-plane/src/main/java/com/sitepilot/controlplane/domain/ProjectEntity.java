package com.sitepilot.controlplane.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "projects")
public class ProjectEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String name;
    private OffsetDateTime createdAt;

    protected ProjectEntity() {}

    public ProjectEntity(String tenantId, String projectId, String name) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.name = name;
        this.createdAt = OffsetDateTime.now();
    }

    public String getTenantId() { return tenantId; }
    public String getProjectId() { return projectId; }
    public String getName() { return name; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
