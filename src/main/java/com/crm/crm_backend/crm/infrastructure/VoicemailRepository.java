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

public interface VoicemailRepository extends JpaRepository<Voicemail, UUID> {
	List<Voicemail> findByOrganizationIdOrderByReceivedAtDesc(UUID organizationId);

	Optional<Voicemail> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
