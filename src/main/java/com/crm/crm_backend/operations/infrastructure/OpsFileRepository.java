package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.OpsFile;

public interface OpsFileRepository extends JpaRepository<OpsFile, UUID> {

	List<OpsFile> findByOrganizationIdAndSpaceOrderByCreatedAtDesc(UUID organizationId, String space);

	List<OpsFile> findByOrganizationIdAndSpaceAndVendorIdOrderByCreatedAtDesc(UUID organizationId, String space, UUID vendorId);

	List<OpsFile> findByOrganizationIdAndStaffIdOrderByCreatedAtDesc(UUID organizationId, UUID staffId);

	Optional<OpsFile> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
