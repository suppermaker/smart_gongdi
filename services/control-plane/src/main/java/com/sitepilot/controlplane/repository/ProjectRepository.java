package com.sitepilot.controlplane.repository;

import com.sitepilot.controlplane.domain.ProjectEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {
    Optional<ProjectEntity> findByTenantIdAndProjectId(String tenantId, String projectId);
}
