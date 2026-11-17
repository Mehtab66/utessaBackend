package com.crm.crm_backend.crm.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.CrmIntegration;

public interface CrmIntegrationRepository extends JpaRepository<CrmIntegration, UUID> {

	List<CrmIntegration> findByOrganizationId(UUID organizationId);

	Optional<CrmIntegration> findByOrganizationIdAndKind(UUID organizationId, String kind);
}
