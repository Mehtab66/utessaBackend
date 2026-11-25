package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.InventoryItem;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

	List<InventoryItem> findByOrganizationIdOrderByNameAsc(UUID organizationId);

	Optional<InventoryItem> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
