package com.crm.crm_backend.workspace.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.workspace.domain.WorkspaceTask;

public interface WorkspaceTaskRepository extends JpaRepository<WorkspaceTask, UUID> {
	List<WorkspaceTask> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	Optional<WorkspaceTask> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
