package com.crm.crm_backend.crm.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.CrmTask;
import com.crm.crm_backend.crm.domain.TaskStatus;

public interface CrmTaskRepository extends JpaRepository<CrmTask, UUID> {

	List<CrmTask> findByOrganizationIdOrderByDueAtAsc(UUID organizationId);

	List<CrmTask> findByOrganizationIdAndPatientIdOrderByDueAtAsc(UUID organizationId, UUID patientId);

	List<CrmTask> findByOrganizationIdAndPatientIdAndStatusInOrderByDueAtAsc(
			UUID organizationId, UUID patientId, Collection<TaskStatus> statuses);

	Optional<CrmTask> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
