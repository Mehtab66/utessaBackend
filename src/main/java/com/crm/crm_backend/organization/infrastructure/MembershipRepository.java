package com.crm.crm_backend.organization.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.organization.domain.Membership;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
	Optional<Membership> findByUserId(UUID userId);
	Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);
}
