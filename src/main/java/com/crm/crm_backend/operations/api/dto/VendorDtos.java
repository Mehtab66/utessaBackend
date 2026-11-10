package com.crm.crm_backend.operations.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.crm.crm_backend.operations.domain.VendorBaaStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class VendorDtos {

	private VendorDtos() {
	}

	public record VendorRequest(
			@NotBlank @Size(max = 255) String name,
			@NotBlank @Size(max = 100) String category,
			@Size(max = 100) String folder,
			@Size(max = 150) String contactName,
			@Size(max = 150) String contactRole,
			@Size(max = 255) String email,
			@Size(max = 50) String phone,
			@Size(max = 255) String website,
			@Size(max = 100) String accountNumber,
			Boolean handlesPhi,
			LocalDate contractStart,
			LocalDate contractEnd,
			Boolean autoRenews,
			Integer cancellationNoticeDays,
			BigDecimal monthlyCost,
			@Size(max = 100) String paymentTerms,
			VendorBaaStatus baaStatus,
			LocalDate baaSignedOn,
			String notes,
			Boolean active,
			List<Map<String, Object>> documents
	) {
	}

	public record VendorResponse(
			UUID id,
			String name,
			String category,
			String folder,
			String contactName,
			String contactRole,
			String email,
			String phone,
			String website,
			String accountNumber,
			boolean handlesPhi,
			LocalDate contractStart,
			LocalDate contractEnd,
			boolean autoRenews,
			Integer cancellationNoticeDays,
			BigDecimal monthlyCost,
			String paymentTerms,
			VendorBaaStatus baaStatus,
			LocalDate baaSignedOn,
			String notes,
			boolean active,
			List<Map<String, Object>> documents,
			Instant createdAt,
			Instant updatedAt
	) {
	}
}
