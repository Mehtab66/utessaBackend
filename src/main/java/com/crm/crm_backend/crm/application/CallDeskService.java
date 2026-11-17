package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.CallDeskDtos.CallerLookupResponse;
import com.crm.crm_backend.crm.api.dto.CallDeskDtos.CallerMatch;
import com.crm.crm_backend.crm.api.dto.CallDeskDtos.LogCallRequest;
import com.crm.crm_backend.crm.api.dto.CallDeskDtos.LogCallResponse;
import com.crm.crm_backend.crm.api.dto.CallDeskDtos.NewCallerInfo;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.StatusChangeRequest;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.CrmTask;
import com.crm.crm_backend.crm.domain.Opportunity;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.Referral;
import com.crm.crm_backend.crm.domain.ReferralPartner;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.AppointmentRepository;
import com.crm.crm_backend.crm.infrastructure.CrmTaskRepository;
import com.crm.crm_backend.crm.infrastructure.OpportunityRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.ReferralRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * Front-desk call handling. {@link #lookup} is the screen-pop: given a caller's number it
 * returns the existing patient/lead record(s) with their open work, or flags a new caller.
 * The phone-system integration will call the same lookup when a call rings in.
 */
@Service
public class CallDeskService {

	private static final List<ActivityType> CALL_TYPES = List.of(ActivityType.CALL_INBOUND, ActivityType.CALL_OUTBOUND);

	private final PatientRepository patientRepository;
	private final OpportunityRepository opportunityRepository;
	private final AppointmentRepository appointmentRepository;
	private final CrmTaskRepository taskRepository;
	private final ReferralRepository referralRepository;
	private final ActivityRepository activityRepository;
	private final PatientService patientService;
	private final TaskService taskService;
	private final CrmSupport support;

	public CallDeskService(
			PatientRepository patientRepository,
			OpportunityRepository opportunityRepository,
			AppointmentRepository appointmentRepository,
			CrmTaskRepository taskRepository,
			ReferralRepository referralRepository,
			ActivityRepository activityRepository,
			PatientService patientService,
			TaskService taskService,
			CrmSupport support
	) {
		this.patientRepository = patientRepository;
		this.opportunityRepository = opportunityRepository;
		this.appointmentRepository = appointmentRepository;
		this.taskRepository = taskRepository;
		this.referralRepository = referralRepository;
		this.activityRepository = activityRepository;
		this.patientService = patientService;
		this.taskService = taskService;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public CallerLookupResponse lookup(UserPrincipal principal, String phone) {
		support.requireAccess(principal);
		String normalized = CrmSupport.normalizePhone(phone);
		if (normalized == null || normalized.length() < 7) {
			throw ApiException.badRequest("Enter at least 7 digits of the caller's phone number.");
		}
		UUID orgId = support.orgId();
		List<Patient> found = patientRepository.findByPhone(orgId, normalized);
		Map<UUID, ReferralPartner> partners = support.partners();
		Instant now = Instant.now();
		List<CallerMatch> matches = found.stream().map(p -> {
			List<Opportunity> opps = opportunityRepository.findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(orgId, p.getId())
					.stream().filter(Opportunity::isOpen).toList();
			List<Appointment> appts = appointmentRepository.findByOrganizationIdAndPatientIdOrderByStartsAtDesc(orgId, p.getId())
					.stream().filter(a -> a.isActive() && !a.getStartsAt().isBefore(now.minusSeconds(3600)))
					.sorted((a, b) -> a.getStartsAt().compareTo(b.getStartsAt()))
					.toList();
			List<CrmTask> tasks = taskRepository.findByOrganizationIdAndPatientIdOrderByDueAtAsc(orgId, p.getId())
					.stream().filter(CrmTask::isOpen).toList();
			List<Referral> referrals = referralRepository.findByOrganizationIdAndPatientIdOrderByReceivedOnDesc(orgId, p.getId())
					.stream().filter(Referral::isOpen).toList();
			List<Activity> activities = activityRepository.findByOrganizationIdAndPatientIdOrderByOccurredAtDesc(orgId, p.getId());
			ActivityResponse last = activities.stream()
					.filter(a -> a.getActivityType() != ActivityType.SYSTEM)
					.findFirst()
					.map(a -> CrmMapper.activity(a, p))
					.orElse(null);
			long calls = activities.stream().filter(a -> CALL_TYPES.contains(a.getActivityType())).count();
			return new CallerMatch(
					CrmMapper.patient(p, partners.get(p.getReferralPartnerId())),
					opps.stream().map(o -> CrmMapper.opportunity(o, p)).toList(),
					appts.stream().map(a -> CrmMapper.appointment(a, p)).toList(),
					tasks.stream().map(t -> CrmMapper.task(t, p)).toList(),
					referrals.stream().map(r -> CrmMapper.referral(r, p, partners.get(r.getReferralPartnerId()))).toList(),
					last,
					calls
			);
		}).toList();
		return new CallerLookupResponse(phone, normalized, matches.isEmpty(), matches);
	}

	@Transactional
	public LogCallResponse logCall(UserPrincipal principal, LogCallRequest request) {
		support.requireAccess(principal);
		String normalized = CrmSupport.normalizePhone(request.phone());
		if (normalized == null) {
			throw ApiException.badRequest("Phone number is required.");
		}
		boolean createdLead = false;
		Patient patient;
		if (request.patientId() != null) {
			patient = support.requirePatient(request.patientId());
		} else if (request.newCaller() != null) {
			patient = createCallerLead(principal, request.phone(), request.newCaller());
			createdLead = true;
		} else {
			List<Patient> matches = patientRepository.findByPhone(support.orgId(), normalized);
			if (matches.size() != 1) {
				throw ApiException.badRequest(matches.isEmpty()
						? "New caller — enter the caller's name to create a lead."
						: "Several records use this number — pick the caller first.");
			}
			patient = matches.get(0);
		}

		boolean outbound = "OUTBOUND".equalsIgnoreCase(request.direction());
		ActivityType type = outbound ? ActivityType.CALL_OUTBOUND : ActivityType.CALL_INBOUND;
		String outcome = CrmSupport.clean(request.outcome());
		Activity activity = new Activity();
		activity.setPatientId(patient.getId());
		activity.setActivityType(type);
		activity.setSubject((outbound ? "Outbound call" : "Inbound call") + (outcome == null ? "" : " · " + outcome));
		activity.setBody(CrmSupport.clean(request.notes()));
		activity.setPhone(request.phone().trim());
		activity.setCallOutcome(outcome);
		activity.setDurationSeconds(request.durationSeconds());
		activity.setActorUserId(principal.userId());
		activity.setActorName(support.actorName(principal));
		Activity saved = support.save(activity);

		patientService.applyContact(principal, patient, type, outcome, saved.getOccurredAt());
		if (request.nextStatus() != null && request.nextStatus() != patient.getLeadStatus()) {
			patientService.changeStatus(principal, patient.getId(), new StatusChangeRequest(request.nextStatus(), null, null));
		}

		UUID followUpId = null;
		if (request.followUpAt() != null) {
			String title = CrmSupport.clean(request.followUpTitle());
			CrmTask task = taskService.createSystemTask(principal, patient.getId(), null, null,
					title == null ? "Call back " + patient.getFullName() : title, "CALL", patient.getPriority(),
					request.followUpAt(), patient.getOwnerUserId());
			followUpId = task.getId();
		}
		return new LogCallResponse(patient.getId(), createdLead, CrmMapper.activity(saved, patient), followUpId);
	}

	@Transactional(readOnly = true)
	public List<ActivityResponse> recentCalls(UserPrincipal principal, int limit) {
		support.requireAccess(principal);
		List<Activity> calls = activityRepository.findByOrganizationIdAndActivityTypeInOrderByOccurredAtDesc(
				support.orgId(), CALL_TYPES, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
		Map<UUID, Patient> patients = support.patients(calls.stream().map(Activity::getPatientId).toList());
		return calls.stream().map(a -> CrmMapper.activity(a, patients.get(a.getPatientId()))).toList();
	}

	private Patient createCallerLead(UserPrincipal principal, String phone, NewCallerInfo info) {
		PatientRequest request = new PatientRequest(
				info.firstName(), info.lastName(), null, info.dateOfBirth(), null, phone, null,
				CrmSupport.clean(info.email()), "Mobile", null, null, true, false, false, false,
				null, null, null, null, info.source() == null || info.source().isBlank() ? "Phone call" : info.source(),
				null, null, null, CrmSupport.clean(info.interestedService()), null, null,
				CrmSupport.clean(info.reasonForVisit()), CrmSupport.clean(info.insurancePayer()), null, null, false,
				null, null, info.priority() == null ? Priority.MEDIUM : info.priority(),
				null, null, null, null, null, null, null, null, null, null);
		return patientService.createRecord(principal, request, true, false);
	}
}
