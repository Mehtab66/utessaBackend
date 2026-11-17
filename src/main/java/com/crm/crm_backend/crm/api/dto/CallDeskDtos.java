package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentResponse;
import com.crm.crm_backend.crm.api.dto.OpportunityDtos.OpportunityResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.ReferralResponse;
import com.crm.crm_backend.crm.api.dto.TaskDtos.TaskResponse;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Priority;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class CallDeskDtos {

	private CallDeskDtos() {
	}

	/** Result of a screen-pop lookup: existing patient/lead record(s) or a new caller. */
	public record CallerLookupResponse(
			String phone,
			String normalized,
			boolean newCaller,
			List<CallerMatch> matches
	) {
	}

	public record CallerMatch(
			PatientResponse patient,
			List<OpportunityResponse> openOpportunities,
			List<AppointmentResponse> upcomingAppointments,
			List<TaskResponse> openTasks,
			List<ReferralResponse> openReferrals,
			ActivityResponse lastActivity,
			long totalCalls
	) {
	}

	public record NewCallerInfo(
			@NotBlank(message = "Caller first name is required.") @Size(max = 100) String firstName,
			@Size(max = 100) String lastName,
			@Size(max = 255) String email,
			LocalDate dateOfBirth,
			String reasonForVisit,
			@Size(max = 150) String interestedService,
			@Size(max = 150) String insurancePayer,
			@Size(max = 100) String source,
			Priority priority
	) {
	}

	public record LogCallRequest(
			@NotBlank(message = "Phone number is required.") @Size(max = 50) String phone,
			@Size(max = 20) String direction,
			UUID patientId,
			@Valid NewCallerInfo newCaller,
			@Size(max = 100) String outcome,
			String notes,
			@PositiveOrZero Integer durationSeconds,
			LeadStatus nextStatus,
			Instant followUpAt,
			@Size(max = 255) String followUpTitle
	) {
	}

	public record LogCallResponse(
			UUID patientId,
			boolean createdLead,
			ActivityResponse activity,
			UUID followUpTaskId
	) {
	}
}
