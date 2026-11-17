package com.crm.crm_backend.crm.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.Fax;
import com.crm.crm_backend.crm.domain.PhoneConfig;
import com.crm.crm_backend.crm.domain.PhoneMessage;
import com.crm.crm_backend.crm.domain.Voicemail;

public interface FaxRepository extends JpaRepository<Fax, UUID> {
	List<FaxSummary> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	Optional<Fax> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
