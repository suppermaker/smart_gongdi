package com.sitepilot.controlplane.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "risks")
public class RiskEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String riskId;
    private String sourceEventId;
    private String title;

    @Enumerated(EnumType.STRING)
    private RiskStatus status;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    protected RiskEntity() {}

    public RiskEntity(String tenantId, String projectId, String riskId, String sourceEventId, String title) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.riskId = riskId;
        this.sourceEventId = sourceEventId;
        this.title = title;
        this.status = RiskStatus.DETECTED;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = createdAt;
    }

    public void transition(RiskStatus target) {
        status.requireTransitionTo(target);
        status = target;
        updatedAt = OffsetDateTime.now();
    }

    public String getRiskId() { return riskId; }
    public String getSourceEventId() { return sourceEventId; }
    public String getTitle() { return title; }
    public RiskStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
