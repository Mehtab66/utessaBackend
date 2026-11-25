package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.InventoryRequest;

public interface InventoryRequestRepository extends JpaRepository<InventoryRequest, UUID> {

	List<InventoryRequest> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	Optional<InventoryRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
