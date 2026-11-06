package com.sitepilot.controlplane.repository;

import com.sitepilot.controlplane.domain.EvidenceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceRepository extends JpaRepository<EvidenceEntity, Long> {
    Optional<EvidenceEntity> findByTenantIdAndProjectIdAndEvidenceId(
            String tenantId, String projectId, String evidenceId);

    List<EvidenceEntity> findAllByTenantIdAndProjectIdAndRiskRef(
            String tenantId, String projectId, String riskRef);
}
