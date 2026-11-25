package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.Payer;

public interface PayerRepository extends JpaRepository<Payer, UUID> {

	List<Payer> findByOrganizationIdOrderBySortOrderAscNameAsc(UUID organizationId);

	Optional<Payer> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
