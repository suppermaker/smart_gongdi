package com.sitepilot.controlplane.routing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.sitepilot.controlplane.domain.BaseEntity;

@Entity
@Table(name = "routing_decisions")
public class RoutingDecisionEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String taskId;
    private int graphRevision;
    private String policyVersion;
    private String provider;
    private String modelVersion;
    private String inputDigest;
    private String decisionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String compiledDag;

    private String status;
    private OffsetDateTime createdAt;

    protected RoutingDecisionEntity() {}

    public RoutingDecisionEntity(String tenantId, String projectId, String taskId,
                                 int graphRevision, String policyVersion, String provider,
                                 String modelVersion, String inputDigest, String decisionId,
                                 String decision, String compiledDag, String status) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.taskId = taskId;
        this.graphRevision = graphRevision;
        this.policyVersion = policyVersion;
        this.provider = provider;
        this.modelVersion = modelVersion;
        this.inputDigest = inputDigest;
        this.decisionId = decisionId;
        this.decision = decision;
        this.compiledDag = compiledDag;
        this.status = status;
        this.createdAt = OffsetDateTime.now();
    }

    public String getTaskId() { return taskId; }
    public int getGraphRevision() { return graphRevision; }
    public String getPolicyVersion() { return policyVersion; }
    public String getInputDigest() { return inputDigest; }
    public String getDecision() { return decision; }
    public String getCompiledDag() { return compiledDag; }
    public String getStatus() { return status; }
}
