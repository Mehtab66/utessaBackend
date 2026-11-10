package com.crm.crm_backend.operations.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.crm.crm_backend.operations.domain.LeadStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class LeadDtos {

	private LeadDtos() {
	}

	public record LeadRequest(
			@NotBlank @Size(max = 255) String fullName,
			@Size(max = 50) String phone,
			@Size(max = 255) String email,
			@Size(max = 100) String source,
			@Size(max = 50) String preferredContact,
			@Size(max = 255) String referralSourceDetail,
			@Size(max = 150) String interestedService,
			@Size(max = 150) String preferredProvider,
			@Size(max = 150) String insurancePayer,
			LeadStatus status,
			@Size(max = 150) String ownerName,
			LocalDate nextFollowUp,
			Instant lastContactAt,
			@Size(max = 100) String callOutcome,
			String notes
	) {
	}

	public record LeadResponse(
			UUID id,
			String fullName,
			String phone,
			String email,
			String source,
			String preferredContact,
			String referralSourceDetail,
			String interestedService,
			String preferredProvider,
			String insurancePayer,
			LeadStatus status,
			String ownerName,
			LocalDate nextFollowUp,
			Instant lastContactAt,
			String callOutcome,
			String notes,
			Instant createdAt,
			Instant updatedAt
	) {
	}
}
