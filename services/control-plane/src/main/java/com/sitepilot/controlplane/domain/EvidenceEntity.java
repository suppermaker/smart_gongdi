package com.sitepilot.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "evidence")
public class EvidenceEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String evidenceId;
    private String riskRef;
    private String sourceType;
    private String sourceRef;
    private String sourceVersion;

    @Column(columnDefinition = "text")
    private String claim;

    private OffsetDateTime observedAt;
    private OffsetDateTime ingestedAt;
    private OffsetDateTime validUntil;
    private String digest;

    @Column(columnDefinition = "text")
    private String quoteText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String structuredValue;

    private String reliability;
    private String completeness;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String toolProvenance;
    private String semanticHash;

    protected EvidenceEntity() {}

    public EvidenceEntity(String tenantId, String projectId, String evidenceId, String riskRef,
                          String sourceType, String sourceRef, String sourceVersion, String claim,
                          OffsetDateTime observedAt, OffsetDateTime validUntil, String digest,
                          String quoteText, String structuredValue, String reliability,
                          String completeness, String toolProvenance, String semanticHash) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.evidenceId = evidenceId;
        this.riskRef = riskRef;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.sourceVersion = sourceVersion;
        this.claim = claim;
        this.observedAt = observedAt;
        this.ingestedAt = OffsetDateTime.now();
        this.validUntil = validUntil;
        this.digest = digest;
        this.quoteText = quoteText;
        this.structuredValue = structuredValue;
        this.reliability = reliability;
        this.completeness = completeness;
        this.toolProvenance = toolProvenance;
        this.semanticHash = semanticHash;
    }

    public boolean hasSemanticHash(String expected) {
        return Objects.equals(semanticHash, expected);
    }

    public String getEvidenceId() { return evidenceId; }
    public String getRiskRef() { return riskRef; }
    public String getClaim() { return claim; }
}
