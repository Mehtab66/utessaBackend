package com.crm.crm_backend.workspace.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.workspace.domain.WorkspaceEvent;

public interface WorkspaceEventRepository extends JpaRepository<WorkspaceEvent, UUID> {
	List<WorkspaceEvent> findByOrganizationIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
			UUID organizationId, Instant to, Instant from);

	Optional<WorkspaceEvent> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
