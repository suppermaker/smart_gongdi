package com.sitepilot.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "source_events")
public class SourceEventEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String eventId;
    private String eventType;
    private OffsetDateTime occurredAt;
    private OffsetDateTime ingestedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String attributes;

    protected SourceEventEntity() {}

    public SourceEventEntity(String tenantId, String projectId, String eventId, String eventType,
                             OffsetDateTime occurredAt, String attributes) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.ingestedAt = OffsetDateTime.now();
        this.attributes = attributes;
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getAttributes() { return attributes; }
}
