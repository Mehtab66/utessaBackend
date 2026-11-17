package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Incoming-call flow: matching, call documentation, new-patient intake, openings, analytics. */
public final class CallFlowDtos {

	private CallFlowDtos() {
	}

	public record LinkPhoneRequest(@NotBlank(message = "Phone number is required.") @Size(max = 50) String phone) {
	}

	/** Documents a call for an existing patient/lead, or for a non-patient caller when patientId is null. */
	public record DocumentCallRequest(
			@NotBlank(message = "Phone number is required.") @Size(max = 50) String phone,
			@Size(max = 20) String direction,
			UUID patientId,
			@Size(max = 150) String callerName,
			@Size(max = 40) String callerType,
			@Size(max = 100) String reason,
			@Size(max = 100) String outcome,
			String notes,
			@PositiveOrZero Integer durationSeconds,
			LocalDate callBackBy,
			Boolean fileToEhr,
			@Size(max = 40) String phoneLine
	) {
	}

	/** New caller: either book a new-patient appointment (mode BOOK) or save as a lead (mode LEAD). */
	public record IntakeRequest(
			@NotBlank String mode,
			@NotBlank(message = "Phone number is required.") @Size(max = 50) String phone,
			@NotBlank(message = "First name is required.") @Size(max = 100) String firstName,
			@NotBlank(message = "Last name is required.") @Size(max = 100) String lastName,
			LocalDate dateOfBirth,
			@Size(max = 30) String gender,
			@Size(max = 50) String preferredLanguage,
			@Email(message = "Email is not valid.") @Size(max = 255) String email,
			@Size(max = 255) String street,
			@Size(max = 100) String city,
			@Size(max = 50) String state,
			@Size(max = 20) String zip,
			@Size(max = 255) String referredBy,
			UUID referralPartnerId,
			@Size(max = 20) String coverageType,
			@Size(max = 150) String insurancePayer,
			@Size(max = 100) String insuranceMemberId,
			@Size(max = 100) String insuranceGroup,
			@Size(max = 30) String policyHolder,
			String reasonForVisit,
			@Size(max = 50) String appointmentType,
			Integer durationMinutes,
			@Size(max = 150) String providerName,
			@Size(max = 150) String location,
			Instant startsAt,
			Boolean smsConsent,
			@Size(max = 150) String leadReason,
			LocalDate followUpOn,
			String callNotes,
			@PositiveOrZero Integer durationSeconds,
			@Size(max = 40) String phoneLine
	) {
	}

	public record IntakeResponse(
			PatientResponse patient,
			AppointmentResponse appointment,
			ActivityResponse call,
			List<SyncStep> steps
	) {
	}

	public record SyncStep(String label, String detail, String state) {
	}

	public record ProviderOption(String name, String specialty, String location) {
	}

	public record OpeningDay(LocalDate date, List<Instant> slots) {
	}

	// ── recalls ──────────────────────────────────────────────────────────

	public record RecallResponse(
			UUID id,
			UUID patientId,
			String patientName,
			String patientPhone,
			LocalDate patientDob,
			String mrn,
			boolean smsConsent,
			UUID appointmentId,
			Instant missedAt,
			String missType,
			String visitType,
			String providerName,
			String source,
			String status,
			int attempts,
			String lastOutcome,
			Instant lastAttemptAt,
			LocalDate nextAttemptOn,
			UUID rebookedAppointmentId,
			Instant rebookedAt,
			String closeReason,
			String notes,
			Instant createdAt
	) {
	}

	public record RecallAttemptRequest(
			@NotBlank(message = "Choose an outcome.") String outcome,
			Instant newStartsAt,
			@Size(max = 150) String providerName,
			@Size(max = 50) String appointmentType,
			LocalDate tryAgainOn,
			@Size(max = 255) String closeReason,
			String notes,
			@PositiveOrZero Integer durationSeconds
	) {
	}

	public record RecallImportRow(
			@Size(max = 100) String firstName,
			@Size(max = 100) String lastName,
			LocalDate dateOfBirth,
			@Size(max = 50) String phone,
			@Size(max = 100) String mrn,
			Instant missedAt,
			@Size(max = 100) String visitType,
			@Size(max = 40) String status,
			@Size(max = 150) String providerName
	) {
	}

	public record RecallImportRequest(
			@NotNull List<RecallImportRow> rows,
			@Size(max = 20) String source
	) {
	}

	public record RecallImportResponse(int added, int linkedToExisting, int newPatients, int skippedDuplicates, int invalid,
			List<String> messages) {
	}

	// ── analytics ────────────────────────────────────────────────────────

	public record CallAnalytics(
			long callsReceived,
			long callsMade,
			double answerRate,
			long avgDurationSeconds,
			double documentedRate,
			long newPatientsBooked,
			long leadsCreated,
			long matchedOnNewPhone,
			List<DayPoint> perDay,
			List<FunnelStep> newNumberFunnel,
			List<Count> reasons,
			int[][] heatmap,
			List<StaffRow> byStaff,
			List<EhrLogRow> ehrLog
	) {
	}

	public record DayPoint(LocalDate date, long total, long answered, long missed) {
	}

	public record FunnelStep(String label, long count, int pctOfPrevious) {
	}

	public record Count(String label, long count, int pct) {
	}

	public record StaffRow(String name, long calls, long avgHandleSeconds, int documentedPct, long newPatientsBooked) {
	}

	public record EhrLogRow(String what, Instant when, String status) {
	}
}
