package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.TrackerTab;

public interface TrackerTabRepository extends JpaRepository<TrackerTab, UUID> {

	List<TrackerTab> findByOrganizationIdOrderBySortOrderAscNameAsc(UUID organizationId);

	List<TrackerTab> findByOrganizationIdAndScopeOrderBySortOrderAscNameAsc(UUID organizationId, String scope);

	Optional<TrackerTab> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
