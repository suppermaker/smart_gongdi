package com.sitepilot.controlplane.repository;

import com.sitepilot.controlplane.domain.OutboxEventEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {
    Optional<OutboxEventEntity> findByTenantIdAndProjectIdAndEventId(
            String tenantId, String projectId, String eventId);
    long countByTenantIdAndProjectId(String tenantId, String projectId);
}
