package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.Privilege;

public interface PrivilegeRepository extends JpaRepository<Privilege, UUID> {

	List<Privilege> findByOrganizationId(UUID organizationId);

	Optional<Privilege> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
