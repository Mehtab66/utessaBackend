package com.crm.crm_backend.organization.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.organization.domain.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
	boolean existsBySlug(String slug);
	Optional<Organization> findBySlug(String slug);
}
