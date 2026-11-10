package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.LeadStatus;
import com.crm.crm_backend.operations.domain.PatientLead;

public interface PatientLeadRepository extends JpaRepository<PatientLead, UUID> {

	List<PatientLead> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	Optional<PatientLead> findByIdAndOrganizationId(UUID id, UUID organizationId);

	long countByOrganizationId(UUID organizationId);

	long countByOrganizationIdAndStatusIn(UUID organizationId, List<LeadStatus> statuses);
}
