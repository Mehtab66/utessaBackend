package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.Facility;

public interface FacilityRepository extends JpaRepository<Facility, UUID> {

	List<Facility> findByOrganizationIdOrderByNameAsc(UUID organizationId);

	Optional<Facility> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
