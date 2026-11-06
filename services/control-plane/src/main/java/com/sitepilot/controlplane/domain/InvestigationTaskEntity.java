package com.sitepilot.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Objects;

@Entity
@Table(name = "investigation_tasks")
public class InvestigationTaskEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String taskId;

    @Column(columnDefinition = "text")
    private String goal;

    private String riskRef;
    private String status;
    private int graphRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String budgets;
    private String semanticHash;

    protected InvestigationTaskEntity() {}

    public InvestigationTaskEntity(String tenantId, String projectId, String taskId, String goal,
                                   String riskRef, String status, int graphRevision, String budgets, String semanticHash) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.taskId = taskId;
        this.goal = goal;
        this.riskRef = riskRef;
        this.status = status;
        this.graphRevision = graphRevision;
        this.budgets = budgets;
        this.semanticHash = semanticHash;
    }

    public boolean hasSemanticHash(String expected) {
        return Objects.equals(semanticHash, expected);
    }

    public String getTaskId() { return taskId; }
    public String getGoal() { return goal; }
    public String getRiskRef() { return riskRef; }
    public String getStatus() { return status; }
    public int getGraphRevision() { return graphRevision; }
    public String getBudgets() { return budgets; }
}
