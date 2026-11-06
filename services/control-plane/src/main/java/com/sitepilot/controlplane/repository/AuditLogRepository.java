package com.sitepilot.controlplane.repository;

import com.sitepilot.controlplane.domain.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
    long countByTenantIdAndProjectId(String tenantId, String projectId);
}
