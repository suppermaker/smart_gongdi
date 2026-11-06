package com.sitepilot.controlplane.repository;

import com.sitepilot.controlplane.domain.SourceEventEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceEventRepository extends JpaRepository<SourceEventEntity, Long> {
    Optional<SourceEventEntity> findByTenantIdAndProjectIdAndEventId(
            String tenantId, String projectId, String eventId);
    long countByTenantIdAndProjectId(String tenantId, String projectId);
}
