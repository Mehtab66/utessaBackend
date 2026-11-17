package com.crm.crm_backend.crm.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.ReferralPartner;

public interface ReferralPartnerRepository extends JpaRepository<ReferralPartner, UUID> {

	List<ReferralPartner> findByOrganizationIdOrderByNameAsc(UUID organizationId);

	Optional<ReferralPartner> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
