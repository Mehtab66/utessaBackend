package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.crm.crm_backend.crm.domain.AppointmentStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AppointmentDtos {

	private AppointmentDtos() {
	}

	public record AppointmentRequest(
			@NotNull(message = "Select a patient or lead.") UUID patientId,
			UUID opportunityId,
			UUID referralId,
			@Size(max = 255) String title,
			@Size(max = 50) String appointmentType,
			@Size(max = 30) String mode,
			AppointmentStatus status,
			@NotNull(message = "Start date & time is required.") Instant startsAt,
			@Min(5) @Max(600) Integer durationMinutes,
			@Size(max = 150) String providerName,
			@Size(max = 150) String location,
			UUID assignedUserId,
			@Size(max = 255) String cancelReason,
			String notes
	) {
	}

	public record AppointmentResponse(
			UUID id,
			UUID patientId,
			String patientName,
			String patientPhone,
			Lifecycle patientLifecycle,
			UUID opportunityId,
			UUID referralId,
			String title,
			String appointmentType,
			String mode,
			AppointmentStatus status,
			Instant startsAt,
			Instant endsAt,
			int durationMinutes,
			String providerName,
			String location,
			UUID assignedUserId,
			String assignedName,
			String ehrAppointmentId,
			String ehrSyncStatus,
			String cancelReason,
			String notes,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record AppointmentStatusRequest(
			@NotNull(message = "Status is required.") AppointmentStatus status,
			@Size(max = 255) String reason
	) {
	}
}
