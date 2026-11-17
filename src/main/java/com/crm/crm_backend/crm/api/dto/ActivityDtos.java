package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.crm.crm_backend.crm.domain.ActivityType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ActivityDtos {

	private ActivityDtos() {
	}

	public record ActivityRequest(
			@NotNull(message = "Activity type is required.") ActivityType type,
			@Size(max = 255) String subject,
			String body,
			@Size(max = 50) String phone,
			@Size(max = 100) String callOutcome,
			@PositiveOrZero Integer durationSeconds,
			Instant occurredAt,
			UUID opportunityId,
			UUID referralId
	) {
	}

	public record ActivityResponse(
			UUID id,
			UUID patientId,
			String patientName,
			UUID opportunityId,
			UUID referralId,
			ActivityType type,
			String subject,
			String body,
			String phone,
			String callOutcome,
			Integer durationSeconds,
			String actorName,
			Instant occurredAt,
			String callReason,
			String callerName,
			String callerType,
			String phoneLine,
			String ehrSyncStatus
	) {
	}
}
