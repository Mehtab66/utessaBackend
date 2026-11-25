package com.crm.crm_backend.timeclock.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.timeclock.domain.PtoRequest;

public interface PtoRequestRepository extends JpaRepository<PtoRequest, UUID> {

	Optional<PtoRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

	List<PtoRequest> findByOrganizationIdOrderByStartDateDesc(UUID organizationId);

	List<PtoRequest> findByOrganizationIdAndUserIdOrderByStartDateDesc(UUID organizationId, UUID userId);
}
