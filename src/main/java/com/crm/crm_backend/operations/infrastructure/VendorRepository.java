package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.Vendor;
import com.crm.crm_backend.operations.domain.VendorBaaStatus;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

	List<Vendor> findByOrganizationIdOrderByNameAsc(UUID organizationId);

	Optional<Vendor> findByIdAndOrganizationId(UUID id, UUID organizationId);

	long countByOrganizationIdAndActiveTrue(UUID organizationId);

	long countByOrganizationIdAndBaaStatusIn(UUID organizationId, List<VendorBaaStatus> statuses);
}
