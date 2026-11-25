package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.SpecialtyTemplate;

public interface SpecialtyTemplateRepository extends JpaRepository<SpecialtyTemplate, UUID> {

	List<SpecialtyTemplate> findByOrganizationIdOrderBySortOrderAscNameAsc(UUID organizationId);

	Optional<SpecialtyTemplate> findByIdAndOrganizationId(UUID id, UUID organizationId);

	boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);
}
