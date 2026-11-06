package com.sitepilot.controlplane.routing;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingDecisionRepository extends JpaRepository<RoutingDecisionEntity, Long> {
    Optional<RoutingDecisionEntity> findByTenantIdAndProjectIdAndTaskIdAndGraphRevisionAndPolicyVersion(
            String tenantId, String projectId, String taskId, int graphRevision, String policyVersion);

    long countByTenantIdAndProjectIdAndTaskId(String tenantId, String projectId, String taskId);
}
