package com.crm.crm_backend.crm.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.Appointment;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

	List<Appointment> findByOrganizationIdOrderByStartsAtAsc(UUID organizationId);

	List<Appointment> findByOrganizationIdAndStartsAtBetweenOrderByStartsAtAsc(
			UUID organizationId, Instant from, Instant to);

	List<Appointment> findByOrganizationIdAndPatientIdOrderByStartsAtDesc(UUID organizationId, UUID patientId);

	Optional<Appointment> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
