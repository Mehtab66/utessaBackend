package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.PayerEnrollment;

public interface PayerEnrollmentRepository extends JpaRepository<PayerEnrollment, UUID> {

	List<PayerEnrollment> findByOrganizationId(UUID organizationId);

	List<PayerEnrollment> findByOrganizationIdAndStaffId(UUID organizationId, UUID staffId);

	Optional<PayerEnrollment> findByIdAndOrganizationId(UUID id, UUID organizationId);

	boolean existsByOrganizationIdAndStaffIdAndPayerId(UUID organizationId, UUID staffId, UUID payerId);
}
