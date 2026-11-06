package com.sitepilot.controlplane.contextassembly;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContextSnapshotRepository extends JpaRepository<ContextSnapshotEntity, Long> {
    Optional<ContextSnapshotEntity> findByTenantIdAndProjectIdAndTaskIdAndAgentTaskIdAndGraphRevisionAndContextPolicyVersionAndPromptVersion(
            String tenantId, String projectId, String taskId, String agentTaskId,
            int graphRevision, String contextPolicyVersion, String promptVersion);

    Optional<ContextSnapshotEntity> findTopByTenantIdAndProjectIdAndTaskIdAndAgentTaskIdAndContextPolicyVersionAndPromptVersionAndGraphRevisionLessThanOrderByGraphRevisionDesc(
            String tenantId, String projectId, String taskId, String agentTaskId,
            String contextPolicyVersion, String promptVersion, int graphRevision);

    long countByTenantIdAndProjectIdAndTaskId(String tenantId, String projectId, String taskId);
}
