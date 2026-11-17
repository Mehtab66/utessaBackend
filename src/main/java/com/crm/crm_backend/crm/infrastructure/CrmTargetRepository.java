package com.crm.crm_backend.crm.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.CrmTarget;

public interface CrmTargetRepository extends JpaRepository<CrmTarget, UUID> {

	List<CrmTarget> findByOrganizationIdOrderByPeriodStartDesc(UUID organizationId);

	Optional<CrmTarget> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
