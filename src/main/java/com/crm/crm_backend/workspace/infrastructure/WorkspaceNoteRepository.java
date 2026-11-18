package com.crm.crm_backend.workspace.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crm.crm_backend.workspace.domain.WorkspaceNote;

public interface WorkspaceNoteRepository extends JpaRepository<WorkspaceNote, UUID> {
	@Query("select n from WorkspaceNote n where n.organizationId = :org and (n.ownerUserId = :user or n.shared = true) "
			+ "order by n.pinned desc, n.updatedAt desc")
	List<WorkspaceNote> findVisible(@Param("org") UUID organizationId, @Param("user") UUID userId);

	Optional<WorkspaceNote> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
