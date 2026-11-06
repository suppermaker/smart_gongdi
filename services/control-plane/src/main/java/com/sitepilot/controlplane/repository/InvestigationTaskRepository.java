package com.sitepilot.controlplane.repository;

import com.sitepilot.controlplane.domain.InvestigationTaskEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationTaskRepository extends JpaRepository<InvestigationTaskEntity, Long> {
    Optional<InvestigationTaskEntity> findByTenantIdAndProjectIdAndTaskId(
            String tenantId, String projectId, String taskId);
}
