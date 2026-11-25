package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.OpsFolder;

public interface OpsFolderRepository extends JpaRepository<OpsFolder, UUID> {

	List<OpsFolder> findByOrganizationIdAndSpaceAndVendorIdIsNullOrderBySortOrderAscNameAsc(UUID organizationId, String space);

	List<OpsFolder> findByOrganizationIdAndSpaceAndVendorIdOrderBySortOrderAscNameAsc(UUID organizationId, String space, UUID vendorId);

	Optional<OpsFolder> findByIdAndOrganizationId(UUID id, UUID organizationId);

	long countByOrganizationIdAndSpaceAndVendorId(UUID organizationId, String space, UUID vendorId);
}
