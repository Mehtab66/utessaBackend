package com.crm.crm_backend.crm.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.Recall;

public interface RecallRepository extends JpaRepository<Recall, UUID> {

	List<Recall> findByOrganizationIdOrderByMissedAtDesc(UUID organizationId);

	Optional<Recall> findByIdAndOrganizationId(UUID id, UUID organizationId);

	Optional<Recall> findByOrganizationIdAndPatientIdAndMissedAt(UUID organizationId, UUID patientId, Instant missedAt);

	Optional<Recall> findByOrganizationIdAndAppointmentId(UUID organizationId, UUID appointmentId);
}
