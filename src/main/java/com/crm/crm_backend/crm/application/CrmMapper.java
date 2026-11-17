package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.time.LocalDate;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentResponse;
import com.crm.crm_backend.crm.api.dto.OpportunityDtos.OpportunityResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.ReferralResponse;
import com.crm.crm_backend.crm.api.dto.TaskDtos.TaskResponse;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.CrmTask;
import com.crm.crm_backend.crm.domain.Opportunity;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Referral;
import com.crm.crm_backend.crm.domain.ReferralPartner;

/** Entity -> response mapping. Related records may be null when they were deleted. */
public final class CrmMapper {

	private CrmMapper() {
	}

	public static PatientResponse patient(Patient p, ReferralPartner partner) {
		return new PatientResponse(
				p.getId(), p.getLifecycleStage(), p.getLeadStatus(), p.getPriority(),
				p.getFullName(), p.getFirstName(), p.getLastName(), p.getPreferredName(),
				p.getDateOfBirth(), p.getGender(), p.getPhone(), p.getAltPhone(), p.getEmail(),
				p.getPreferredContact(), p.getPreferredLanguage(), p.getBestTimeToCall(),
				p.isConsentCall(), p.isConsentSms(), p.isConsentEmail(), p.isDoNotContact(),
				p.getStreet(), p.getCity(), p.getState(), p.getZip(),
				p.getSource(), p.getSourceDetail(), p.getCampaign(),
				p.getReferralPartnerId(), partner == null ? null : partner.getName(),
				p.getInterestedService(), p.getPreferredProvider(), p.getPreferredLocation(), p.getReasonForVisit(),
				p.getInsurancePayer(), p.getInsurancePlan(), p.getInsuranceMemberId(), p.isInsuranceVerified(),
				p.getOwnerUserId(), p.getOwnerName(), p.getNextFollowUp(), p.getLastContactAt(), p.getCallOutcome(),
				p.getLostReason(), p.getMrn(), p.getEhrPatientId(), p.getEhrSyncStatus(), p.getEhrSyncedAt(),
				p.getConvertedAt(), p.getTags(), p.getNotes(), p.getCreatedAt(), p.getUpdatedAt(),
				p.getCoverageType(), p.getInsuranceGroup(), p.getPolicyHolder(), p.isSmsReminders()
		);
	}

	public static OpportunityResponse opportunity(Opportunity o, Patient p) {
		return new OpportunityResponse(
				o.getId(), o.getPatientId(),
				p == null ? null : p.getFullName(), p == null ? null : p.getPhone(),
				p == null ? null : p.getLifecycleStage(),
				o.getReferralId(), o.getName(), o.getServiceLine(), o.getStage(), o.getPriority(),
				o.getEstimatedValue(), o.getProbability(), o.getExpectedCloseDate(), o.getProviderName(),
				o.getOwnerUserId(), o.getOwnerName(), o.getLostReason(), o.getClosedAt(), o.getNotes(),
				o.getCreatedAt(), o.getUpdatedAt()
		);
	}

	public static AppointmentResponse appointment(Appointment a, Patient p) {
		return new AppointmentResponse(
				a.getId(), a.getPatientId(),
				p == null ? null : p.getFullName(), p == null ? null : p.getPhone(),
				p == null ? null : p.getLifecycleStage(),
				a.getOpportunityId(), a.getReferralId(), a.getTitle(), a.getAppointmentType(), a.getMode(),
				a.getStatus(), a.getStartsAt(), a.getStartsAt().plusSeconds(a.getDurationMinutes() * 60L),
				a.getDurationMinutes(), a.getProviderName(), a.getLocation(), a.getAssignedUserId(),
				a.getAssignedName(), a.getEhrAppointmentId(), a.getEhrSyncStatus(), a.getCancelReason(),
				a.getNotes(), a.getCreatedAt(), a.getUpdatedAt()
		);
	}

	public static TaskResponse task(CrmTask t, Patient p) {
		boolean overdue = t.isOpen() && t.getDueAt() != null && t.getDueAt().isBefore(Instant.now());
		return new TaskResponse(
				t.getId(), t.getPatientId(),
				p == null ? null : p.getFullName(), p == null ? null : p.getPhone(),
				t.getOpportunityId(), t.getReferralId(), t.getTitle(), t.getTaskType(), t.getPriority(),
				t.getStatus(), t.getDueAt(), overdue, t.getAssignedUserId(), t.getAssignedName(),
				t.getCompletedAt(), t.getCompletedByName(), t.getNotes(), t.getCreatedByName(),
				t.getCreatedAt(), t.getUpdatedAt()
		);
	}

	public static ReferralResponse referral(Referral r, Patient p, ReferralPartner partner) {
		boolean overdue = r.isOpen() && r.getDueOn() != null && r.getDueOn().isBefore(LocalDate.now());
		return new ReferralResponse(
				r.getId(), r.getPatientId(),
				p == null ? null : p.getFullName(), p == null ? null : p.getPhone(),
				r.getReferralPartnerId(), partner == null ? null : partner.getName(),
				r.getDirection(), r.getStatus(), r.getPriority(), r.getReferringProviderName(),
				r.getReferringProviderNpi(), r.getReferredToProvider(), r.getServiceRequested(), r.getReason(),
				r.getDiagnosisCodes(), r.getInsurancePayer(), r.getAuthorizationNumber(),
				r.getAuthorizationExpiresOn(), r.getReceivedOn(), r.getDueOn(), r.getAssignedUserId(),
				r.getAssignedName(), r.getClosedReason(), r.getClosedAt(), r.getNotes(), overdue,
				r.getCreatedAt(), r.getUpdatedAt()
		);
	}

	public static ActivityResponse activity(Activity a, Patient p) {
		return new ActivityResponse(
				a.getId(), a.getPatientId(), p == null ? null : p.getFullName(),
				a.getOpportunityId(), a.getReferralId(), a.getActivityType(), a.getSubject(), a.getBody(),
				a.getPhone(), a.getCallOutcome(), a.getDurationSeconds(), a.getActorName(), a.getOccurredAt(),
				a.getCallReason(), a.getCallerName(), a.getCallerType(), a.getPhoneLine(), a.getEhrSyncStatus()
		);
	}
}
