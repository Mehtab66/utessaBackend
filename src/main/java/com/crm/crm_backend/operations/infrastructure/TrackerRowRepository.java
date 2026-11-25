package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.TrackerRow;

public interface TrackerRowRepository extends JpaRepository<TrackerRow, UUID> {

	List<TrackerRow> findByOrganizationId(UUID organizationId);

	List<TrackerRow> findByOrganizationIdAndTabIdOrderByCreatedAtAsc(UUID organizationId, UUID tabId);

	List<TrackerRow> findByOrganizationIdAndStaffIdOrderByCreatedAtAsc(UUID organizationId, UUID staffId);

	Optional<TrackerRow> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
