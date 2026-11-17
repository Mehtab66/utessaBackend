package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.ReferralStatus;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ReferralDtos {

	private ReferralDtos() {
	}

	public record PartnerRequest(
			@NotBlank(message = "Partner name is required.") @Size(max = 255) String name,
			@Size(max = 50) String partnerType,
			@Size(max = 30) String relationshipStatus,
			@Size(max = 20) String npi,
			@Size(max = 150) String specialty,
			@Size(max = 150) String contactName,
			@Size(max = 50) String phone,
			@Size(max = 50) String fax,
			@Email(message = "Email is not valid.") @Size(max = 255) String email,
			@Size(max = 255) String street,
			@Size(max = 100) String city,
			@Size(max = 50) String state,
			@Size(max = 20) String zip,
			UUID ownerUserId,
			String notes
	) {
	}

	public record PartnerResponse(
			UUID id,
			String name,
			String partnerType,
			String relationshipStatus,
			String npi,
			String specialty,
			String contactName,
			String phone,
			String fax,
			String email,
			String street,
			String city,
			String state,
			String zip,
			UUID ownerUserId,
			String ownerName,
			Instant lastTouchAt,
			String notes,
			long totalReferrals,
			long openReferrals,
			long referralsLast90Days,
			long patientsReferred,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	/** Either patientId, or the new-patient fields (patientFirstName + phone) must be supplied. */
	public record ReferralRequest(
			UUID patientId,
			@Size(max = 100) String patientFirstName,
			@Size(max = 100) String patientLastName,
			@Size(max = 50) String patientPhone,
			@Size(max = 255) String patientEmail,
			LocalDate patientDateOfBirth,
			UUID referralPartnerId,
			@Size(max = 20) String direction,
			ReferralStatus status,
			Priority priority,
			@Size(max = 150) String referringProviderName,
			@Size(max = 20) String referringProviderNpi,
			@Size(max = 150) String referredToProvider,
			@Size(max = 150) String serviceRequested,
			String reason,
			@Size(max = 255) String diagnosisCodes,
			@Size(max = 150) String insurancePayer,
			@Size(max = 100) String authorizationNumber,
			LocalDate authorizationExpiresOn,
			LocalDate receivedOn,
			LocalDate dueOn,
			UUID assignedUserId,
			String notes
	) {
	}

	public record ReferralResponse(
			UUID id,
			UUID patientId,
			String patientName,
			String patientPhone,
			UUID referralPartnerId,
			String referralPartnerName,
			String direction,
			ReferralStatus status,
			Priority priority,
			String referringProviderName,
			String referringProviderNpi,
			String referredToProvider,
			String serviceRequested,
			String reason,
			String diagnosisCodes,
			String insurancePayer,
			String authorizationNumber,
			LocalDate authorizationExpiresOn,
			LocalDate receivedOn,
			LocalDate dueOn,
			UUID assignedUserId,
			String assignedName,
			String closedReason,
			Instant closedAt,
			String notes,
			boolean overdue,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record ReferralStatusRequest(
			@NotNull(message = "Status is required.") ReferralStatus status,
			@Size(max = 255) String closedReason,
			String note
	) {
	}
}
