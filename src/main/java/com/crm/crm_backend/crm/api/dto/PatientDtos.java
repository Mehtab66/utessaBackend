package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.Priority;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PatientDtos {

	private PatientDtos() {
	}

	public record PatientRequest(
			@NotBlank(message = "First name is required.") @Size(max = 100) String firstName,
			@Size(max = 100) String lastName,
			@Size(max = 100) String preferredName,
			LocalDate dateOfBirth,
			@Size(max = 30) String gender,
			@Size(max = 50) String phone,
			@Size(max = 50) String altPhone,
			@Email(message = "Email is not valid.") @Size(max = 255) String email,
			@Size(max = 50) String preferredContact,
			@Size(max = 50) String preferredLanguage,
			@Size(max = 50) String bestTimeToCall,
			Boolean consentCall,
			Boolean consentSms,
			Boolean consentEmail,
			Boolean doNotContact,
			@Size(max = 255) String street,
			@Size(max = 100) String city,
			@Size(max = 50) String state,
			@Size(max = 20) String zip,
			@Size(max = 100) String source,
			@Size(max = 255) String sourceDetail,
			@Size(max = 150) String campaign,
			UUID referralPartnerId,
			@Size(max = 150) String interestedService,
			@Size(max = 150) String preferredProvider,
			@Size(max = 150) String preferredLocation,
			String reasonForVisit,
			@Size(max = 150) String insurancePayer,
			@Size(max = 150) String insurancePlan,
			@Size(max = 100) String insuranceMemberId,
			Boolean insuranceVerified,
			Lifecycle lifecycleStage,
			LeadStatus leadStatus,
			Priority priority,
			UUID ownerUserId,
			LocalDate nextFollowUp,
			@Size(max = 255) String lostReason,
			@Size(max = 100) String mrn,
			@Size(max = 500) String tags,
			String notes,
			@Size(max = 20) String coverageType,
			@Size(max = 100) String insuranceGroup,
			@Size(max = 30) String policyHolder,
			Boolean smsReminders
	) {
	}

	public record PatientResponse(
			UUID id,
			Lifecycle lifecycleStage,
			LeadStatus leadStatus,
			Priority priority,
			String fullName,
			String firstName,
			String lastName,
			String preferredName,
			LocalDate dateOfBirth,
			String gender,
			String phone,
			String altPhone,
			String email,
			String preferredContact,
			String preferredLanguage,
			String bestTimeToCall,
			boolean consentCall,
			boolean consentSms,
			boolean consentEmail,
			boolean doNotContact,
			String street,
			String city,
			String state,
			String zip,
			String source,
			String sourceDetail,
			String campaign,
			UUID referralPartnerId,
			String referralPartnerName,
			String interestedService,
			String preferredProvider,
			String preferredLocation,
			String reasonForVisit,
			String insurancePayer,
			String insurancePlan,
			String insuranceMemberId,
			boolean insuranceVerified,
			UUID ownerUserId,
			String ownerName,
			LocalDate nextFollowUp,
			Instant lastContactAt,
			String callOutcome,
			String lostReason,
			String mrn,
			String ehrPatientId,
			String ehrSyncStatus,
			Instant ehrSyncedAt,
			Instant convertedAt,
			String tags,
			String notes,
			Instant createdAt,
			Instant updatedAt,
			String coverageType,
			String insuranceGroup,
			String policyHolder,
			boolean smsReminders
	) {
	}

	public record StatusChangeRequest(
			@NotNull(message = "Status is required.") LeadStatus leadStatus,
			@Size(max = 255) String lostReason,
			String note
	) {
	}

	public record ConvertRequest(
			@Size(max = 100) String mrn,
			Boolean pushToEhr,
			String note
	) {
	}

	public record BulkRequest(
			@NotEmpty(message = "Select at least one record.") List<UUID> ids,
			@NotBlank String action,
			String value
	) {
	}

	public record BulkResponse(int updated) {
	}

	/** Leads read from a CSV / Excel file. Every row is saved as a Lead. */
	public record ImportRequest(
			@NotEmpty(message = "The file has no rows to import.")
			@Size(max = 5000, message = "Up to 5,000 rows per file.")
			List<@Valid PatientRequest> rows,
			boolean skipDuplicates
	) {
	}

	/** {@code skipped} lists "row number: reason" for rows that were not added. */
	public record ImportResponse(int created, int duplicates, List<String> skipped) {
	}

	public record DuplicateMatch(
			UUID id,
			String fullName,
			String phone,
			String email,
			Lifecycle lifecycleStage,
			LeadStatus leadStatus,
			String matchedOn
	) {
	}
}
