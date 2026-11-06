package com.sitepilot.controlplane.contextassembly;

import com.sitepilot.controlplane.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "context_snapshots")
public class ContextSnapshotEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String taskId;
    private String agentTaskId;
    private String contextId;
    private int graphRevision;
    private String role;
    private String verificationStage;
    private String contextPolicyVersion;
    private String promptVersion;
    private String objectiveDigest;
    private String contextDigest;
    private String contextMode;
    private String parentContextId;
    private String parentContextDigest;
    private Integer parentGraphRevision;
    private int chainDepth;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String contextPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String manifest;

    private int estimatedTokens;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;

    protected ContextSnapshotEntity() {}

    public ContextSnapshotEntity(String tenantId, String projectId, String taskId,
                                 String agentTaskId, String contextId, int graphRevision,
                                 String role, String verificationStage,
                                 String contextPolicyVersion, String promptVersion,
                                 String objectiveDigest, String contextDigest,
                                 String contextPayload, String manifest,
                                 int estimatedTokens, String status) {
        this(tenantId, projectId, taskId, agentTaskId, contextId, graphRevision, role,
                verificationStage, contextPolicyVersion, promptVersion, objectiveDigest,
                contextDigest, contextPayload, manifest, estimatedTokens, status,
                "FULL", null, null, null, 0);
    }

    public ContextSnapshotEntity(String tenantId, String projectId, String taskId,
                                 String agentTaskId, String contextId, int graphRevision,
                                 String role, String verificationStage,
                                 String contextPolicyVersion, String promptVersion,
                                 String objectiveDigest, String contextDigest,
                                 String contextPayload, String manifest,
                                 int estimatedTokens, String status, String contextMode,
                                 String parentContextId, String parentContextDigest,
                                 Integer parentGraphRevision, int chainDepth) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.taskId = taskId;
        this.agentTaskId = agentTaskId;
        this.contextId = contextId;
        this.graphRevision = graphRevision;
        this.role = role;
        this.verificationStage = verificationStage;
        this.contextPolicyVersion = contextPolicyVersion;
        this.promptVersion = promptVersion;
        this.objectiveDigest = objectiveDigest;
        this.contextDigest = contextDigest;
        this.contextPayload = contextPayload;
        this.manifest = manifest;
        this.estimatedTokens = estimatedTokens;
        this.status = status;
        this.contextMode = contextMode;
        this.parentContextId = parentContextId;
        this.parentContextDigest = parentContextDigest;
        this.parentGraphRevision = parentGraphRevision;
        this.chainDepth = chainDepth;
        this.createdAt = OffsetDateTime.now();
    }

    public String getTaskId() { return taskId; }
    public String getAgentTaskId() { return agentTaskId; }
    public String getContextId() { return contextId; }
    public int getGraphRevision() { return graphRevision; }
    public String getRole() { return role; }
    public String getContextDigest() { return contextDigest; }
    public String getContextPayload() { return contextPayload; }
    public String getManifest() { return manifest; }
    public int getEstimatedTokens() { return estimatedTokens; }
    public String getContextMode() { return contextMode; }
    public String getParentContextId() { return parentContextId; }
    public String getParentContextDigest() { return parentContextDigest; }
    public Integer getParentGraphRevision() { return parentGraphRevision; }
    public int getChainDepth() { return chainDepth; }
}
