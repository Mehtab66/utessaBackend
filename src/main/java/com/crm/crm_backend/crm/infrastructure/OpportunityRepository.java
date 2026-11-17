package com.crm.crm_backend.crm.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.Opportunity;

public interface OpportunityRepository extends JpaRepository<Opportunity, UUID> {

	List<Opportunity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	List<Opportunity> findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(UUID organizationId, UUID patientId);

	Optional<Opportunity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
