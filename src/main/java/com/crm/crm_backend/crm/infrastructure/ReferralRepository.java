package com.crm.crm_backend.crm.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.Referral;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

	List<Referral> findByOrganizationIdOrderByReceivedOnDescCreatedAtDesc(UUID organizationId);

	List<Referral> findByOrganizationIdAndPatientIdOrderByReceivedOnDesc(UUID organizationId, UUID patientId);

	Optional<Referral> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
