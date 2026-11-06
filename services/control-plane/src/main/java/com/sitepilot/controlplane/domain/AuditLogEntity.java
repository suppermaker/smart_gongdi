package com.sitepilot.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tenantId;
    private String projectId;
    private String actor;
    private String action;
    private String aggregateType;
    private String aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    private OffsetDateTime createdAt;

    protected AuditLogEntity() {}

    public AuditLogEntity(String tenantId, String projectId, String actor, String action,
                          String aggregateType, String aggregateId, String details) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.actor = actor;
        this.action = action;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.details = details;
        this.createdAt = OffsetDateTime.now();
    }
}
