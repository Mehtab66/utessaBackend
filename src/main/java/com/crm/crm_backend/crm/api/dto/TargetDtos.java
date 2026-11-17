package com.crm.crm_backend.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class TargetDtos {

	private TargetDtos() {
	}

	public record TargetRequest(
			UUID userId,
			@NotBlank(message = "Metric is required.") @Size(max = 40) String metric,
			@NotNull(message = "Target value is required.") @Positive(message = "Target must be greater than zero.") BigDecimal targetValue,
			@Size(max = 60) String periodLabel,
			@NotNull(message = "Period start is required.") LocalDate periodStart,
			@NotNull(message = "Period end is required.") LocalDate periodEnd,
			String notes
	) {
	}

	public record TargetResponse(
			UUID id,
			UUID userId,
			String assigneeName,
			String metric,
			BigDecimal targetValue,
			BigDecimal achieved,
			int percent,
			String health,
			String periodLabel,
			LocalDate periodStart,
			LocalDate periodEnd,
			String notes,
			String createdByName
	) {
	}
}
