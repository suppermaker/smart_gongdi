package com.sitepilot.controlplane.repository;

import com.sitepilot.controlplane.domain.RiskEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskRepository extends JpaRepository<RiskEntity, Long> {
    Optional<RiskEntity> findByTenantIdAndProjectIdAndRiskId(
            String tenantId, String projectId, String riskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RiskEntity r where r.tenantId = :tenantId and r.projectId = :projectId and r.riskId = :riskId")
    Optional<RiskEntity> findByNamespaceAndRiskIdForUpdate(
            @Param("tenantId") String tenantId,
            @Param("projectId") String projectId,
            @Param("riskId") String riskId);

    Optional<RiskEntity> findByTenantIdAndProjectIdAndSourceEventId(
            String tenantId, String projectId, String sourceEventId);
    long countByTenantIdAndProjectId(String tenantId, String projectId);
}
