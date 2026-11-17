package com.crm.crm_backend.crm.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.OpportunityStage;
import com.crm.crm_backend.crm.domain.Priority;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class OpportunityDtos {

	private OpportunityDtos() {
	}

	public record OpportunityRequest(
			@NotNull(message = "Select a patient or lead.") UUID patientId,
			UUID referralId,
			@NotBlank(message = "Opportunity name is required.") @Size(max = 255) String name,
			@Size(max = 150) String serviceLine,
			OpportunityStage stage,
			Priority priority,
			@PositiveOrZero BigDecimal estimatedValue,
			@Min(0) @Max(100) Integer probability,
			LocalDate expectedCloseDate,
			@Size(max = 150) String providerName,
			UUID ownerUserId,
			@Size(max = 255) String lostReason,
			String notes
	) {
	}

	public record OpportunityResponse(
			UUID id,
			UUID patientId,
			String patientName,
			String patientPhone,
			Lifecycle patientLifecycle,
			UUID referralId,
			String name,
			String serviceLine,
			OpportunityStage stage,
			Priority priority,
			BigDecimal estimatedValue,
			Integer probability,
			LocalDate expectedCloseDate,
			String providerName,
			UUID ownerUserId,
			String ownerName,
			String lostReason,
			Instant closedAt,
			String notes,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record StageRequest(
			@NotNull(message = "Stage is required.") OpportunityStage stage,
			@Size(max = 255) String lostReason,
			String note
	) {
	}
}
