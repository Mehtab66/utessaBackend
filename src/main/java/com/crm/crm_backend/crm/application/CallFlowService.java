package com.crm.crm_backend.crm.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentRequest;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentResponse;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.CallAnalytics;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.Count;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.DayPoint;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.DocumentCallRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.EhrLogRow;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.FunnelStep;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.IntakeRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.IntakeResponse;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.OpeningDay;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.ProviderOption;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.StaffRow;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.SyncStep;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.AppointmentRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.operations.domain.EmploymentStatus;
import com.crm.crm_backend.operations.domain.StaffMember;
import com.crm.crm_backend.operations.infrastructure.StaffMemberRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * The incoming-call workflow from the wireframe: recognise the caller, document the call,
 * link an existing patient's new number, book a new patient (or save a lead), and report on calls.
 */
@Service
public class CallFlowService {

	public static final String OUTCOME_CALL_BACK = "Call back needed";
	public static final String OUTCOME_NEW_PATIENT = "New patient booked";
	public static final String OUTCOME_SAVED_LEAD = "Saved as lead";
	public static final String OUTCOME_MISSED = "Missed";

	private static final List<ActivityType> CALLS = List.of(ActivityType.CALL_INBOUND, ActivityType.CALL_OUTBOUND);
	private static final List<LocalTime> DAY_START_END = List.of(LocalTime.of(8, 0), LocalTime.of(17, 0));

	private final PatientRepository patientRepository;
	private final AppointmentRepository appointmentRepository;
	private final ActivityRepository activityRepository;
	private final StaffMemberRepository staffRepository;
	private final PatientService patientService;
	private final AppointmentService appointmentService;
	private final TaskService taskService;
	private final IntegrationService integrationService;
	private final CrmSupport support;

	public CallFlowService(
			PatientRepository patientRepository,
			AppointmentRepository appointmentRepository,
			ActivityRepository activityRepository,
			StaffMemberRepository staffRepository,
			PatientService patientService,
			AppointmentService appointmentService,
			TaskService taskService,
			IntegrationService integrationService,
			CrmSupport support
	) {
		this.patientRepository = patientRepository;
		this.appointmentRepository = appointmentRepository;
		this.activityRepository = activityRepository;
		this.staffRepository = staffRepository;
		this.patientService = patientService;
		this.appointmentService = appointmentService;
		this.taskService = taskService;
		this.integrationService = integrationService;
		this.support = support;
	}

	// ── 1 · Is this an existing patient on another phone? ───────────────

	@Transactional(readOnly = true)
	public List<PatientResponse> matchByIdentity(UserPrincipal principal, String first, String last, LocalDate dob) {
		support.requireAccess(principal);
		String f = lower(first);
		String l = lower(last);
		if (f == null || l == null || dob == null) {
			throw ApiException.badRequest("Enter first name, last name and date of birth.");
		}
		return patientRepository.findByOrganizationIdOrderByCreatedAtDesc(support.orgId()).stream()
				.filter(p -> dob.equals(p.getDateOfBirth()))
				.filter(p -> l.equals(lower(p.getLastName())))
				.filter(p -> {
					String pf = lower(p.getFirstName());
					return pf != null && (pf.equals(f) || pf.startsWith(f) || f.startsWith(pf));
				})
				.map(patientService::toResponse)
				.toList();
	}

	@Transactional
	public PatientResponse linkPhone(UserPrincipal principal, UUID patientId, String phone) {
		support.requireAccess(principal);
		Patient p = support.requirePatient(patientId);
		String normalized = CrmSupport.normalizePhone(phone);
		if (normalized == null || normalized.length() < 7) {
			throw ApiException.badRequest("Enter a valid phone number.");
		}
		if (normalized.equals(p.getPhoneNormalized()) || normalized.equals(p.getAltPhoneNormalized())) {
			return patientService.toResponse(p);
		}
		String previousAlt = p.getAltPhone();
		p.setAltPhone(phone.trim());
		p.setAltPhoneNormalized(normalized);
		patientRepository.save(p);
		support.log(principal, ActivityType.SYSTEM, p.getId(), null, null,
				"Linked " + phone.trim() + " as a second phone number",
				"Number on file " + (p.getPhone() == null ? "—" : p.getPhone()) + " is kept."
						+ (previousAlt == null ? "" : " Replaced previous second number " + previousAlt + "."));
		return patientService.toResponse(p);
	}

	// ── Document this call ──────────────────────────────────────────────

	@Transactional
	public ActivityResponse documentCall(UserPrincipal principal, DocumentCallRequest r) {
		support.requireAccess(principal);
		Patient patient = r.patientId() == null ? null : support.requirePatient(r.patientId());
		if (patient == null && CrmSupport.clean(r.callerName()) == null) {
			throw ApiException.badRequest("Choose the patient, or enter who called.");
		}
		String outcome = CrmSupport.clean(r.outcome());
		if (OUTCOME_CALL_BACK.equalsIgnoreCase(outcome) && r.callBackBy() == null) {
			throw ApiException.badRequest("Choose the date to call back by.");
		}
		boolean outbound = "OUTBOUND".equalsIgnoreCase(r.direction());
		Activity a = new Activity();
		a.setPatientId(patient == null ? null : patient.getId());
		a.setActivityType(outbound ? ActivityType.CALL_OUTBOUND : ActivityType.CALL_INBOUND);
		String reason = CrmSupport.clean(r.reason());
		a.setSubject(CrmSupport.truncate((outbound ? "Outbound call" : "Inbound call")
				+ (reason == null ? "" : " · " + reason), 255));
		a.setBody(CrmSupport.clean(r.notes()));
		a.setPhone(r.phone().trim());
		a.setCallOutcome(outcome);
		a.setCallReason(reason);
		a.setDurationSeconds(r.durationSeconds());
		a.setPhoneLine(CrmSupport.clean(r.phoneLine()));
		if (patient != null) {
			a.setCallerName(patient.getFullName());
			a.setCallerType(patient.isLead() ? "LEAD" : "PATIENT");
			if (Boolean.TRUE.equals(r.fileToEhr())) {
				a.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
			}
		} else {
			a.setCallerName(r.callerName().trim());
			a.setCallerType(CrmSupport.clean(r.callerType()) == null ? "OTHER" : r.callerType().trim().toUpperCase());
		}
		a.setActorUserId(principal.userId());
		a.setActorName(support.actorName(principal));
		Activity saved = support.save(a);

		if (patient != null) {
			patientService.applyContact(principal, patient, saved.getActivityType(), outcome, saved.getOccurredAt());
			if (r.callBackBy() != null) {
				ZoneId zone = ZoneId.systemDefault();
				taskService.createSystemTask(principal, patient.getId(), null, null,
						"Call back " + patient.getFullName() + (reason == null ? "" : " · " + reason), "CALL",
						patient.getPriority(), r.callBackBy().atTime(LocalTime.of(9, 0)).atZone(zone).toInstant(),
						principal.userId());
			}
		}
		return CrmMapper.activity(saved, patient);
	}

	// ── 2 · New caller: book a new patient, or save as a lead ───────────

	@Transactional
	public IntakeResponse intake(UserPrincipal principal, IntakeRequest r) {
		support.requireAccess(principal);
		boolean book = "BOOK".equalsIgnoreCase(r.mode());
		if (book) {
			if (r.dateOfBirth() == null) {
				throw ApiException.badRequest("Date of birth is required to book a new patient.");
			}
			if (CrmSupport.clean(r.reasonForVisit()) == null) {
				throw ApiException.badRequest("Reason for visit is required.");
			}
			if (r.startsAt() == null) {
				throw ApiException.badRequest("Pick an appointment time.");
			}
			boolean insured = !"SELF_PAY".equalsIgnoreCase(r.coverageType());
			if (insured && (CrmSupport.clean(r.insurancePayer()) == null || CrmSupport.clean(r.insuranceMemberId()) == null)) {
				throw ApiException.badRequest("Insurance plan and member ID are required, or choose self-pay.");
			}
		}

		// Last guard against duplicates: same name + DOB already in Utessa.
		if (r.dateOfBirth() != null) {
			List<PatientResponse> existing = matchByIdentity(principal, r.firstName(), r.lastName(), r.dateOfBirth());
			if (!existing.isEmpty()) {
				throw ApiException.conflict(existing.get(0).fullName() + " (DOB " + r.dateOfBirth()
						+ ") is already in Utessa. Link this number to their profile instead.");
			}
		}

		String callNotes = CrmSupport.clean(r.callNotes());
		PatientRequest pr = new PatientRequest(
				r.firstName(), r.lastName(), null, r.dateOfBirth(), CrmSupport.clean(r.gender()), r.phone(), null,
				CrmSupport.clean(r.email()), "Mobile", CrmSupport.clean(r.preferredLanguage()), null,
				true, Boolean.TRUE.equals(r.smsConsent()), false, false,
				CrmSupport.clean(r.street()), CrmSupport.clean(r.city()), CrmSupport.clean(r.state()), CrmSupport.clean(r.zip()),
				"Phone call", CrmSupport.clean(r.referredBy()), null, r.referralPartnerId(),
				null, book ? CrmSupport.clean(r.providerName()) : null, book ? CrmSupport.clean(r.location()) : null,
				book ? CrmSupport.clean(r.reasonForVisit()) : CrmSupport.clean(r.leadReason()),
				CrmSupport.clean(r.insurancePayer()), null, CrmSupport.clean(r.insuranceMemberId()), false,
				book ? Lifecycle.PATIENT : Lifecycle.LEAD,
				book ? LeadStatus.CONVERTED : LeadStatus.CONTACTED,
				Priority.MEDIUM, principal.userId(), book ? null : r.followUpOn(), null, null, null, callNotes,
				r.coverageType() == null ? "INSURANCE" : r.coverageType(), CrmSupport.clean(r.insuranceGroup()),
				CrmSupport.clean(r.policyHolder()), Boolean.TRUE.equals(r.smsConsent()));
		Patient patient = patientService.createRecord(principal, pr, true, false);
		if (book && patient.getEhrPatientId() == null) {
			patient.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
			patientRepository.save(patient);
		}

		AppointmentResponse appointment = null;
		if (book) {
			appointment = appointmentService.create(principal, new AppointmentRequest(
					patient.getId(), null, null, null,
					r.appointmentType() == null ? "NEW_PATIENT" : r.appointmentType(),
					"TELEHEALTH".equalsIgnoreCase(r.appointmentType()) ? "TELEHEALTH" : "IN_PERSON",
					null, r.startsAt(), r.durationMinutes() == null ? 40 : r.durationMinutes(),
					CrmSupport.clean(r.providerName()), CrmSupport.clean(r.location()), principal.userId(), null,
					CrmSupport.clean(r.reasonForVisit())));
		}

		Activity call = new Activity();
		call.setPatientId(patient.getId());
		call.setActivityType(ActivityType.CALL_INBOUND);
		call.setSubject(book ? "Inbound call · New patient appointment booked" : "Inbound call · Saved as lead");
		call.setBody(callNotes);
		call.setPhone(r.phone().trim());
		call.setCallOutcome(book ? OUTCOME_NEW_PATIENT : OUTCOME_SAVED_LEAD);
		call.setCallReason(book ? "New patient appointment" : CrmSupport.clean(r.leadReason()));
		call.setCallerName(patient.getFullName());
		call.setCallerType("NEW_CALLER");
		call.setDurationSeconds(r.durationSeconds());
		call.setPhoneLine(CrmSupport.clean(r.phoneLine()));
		call.setActorUserId(principal.userId());
		call.setActorName(support.actorName(principal));
		if (book) {
			call.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
		}
		Activity savedCall = support.save(call);
		patient.setLastContactAt(savedCall.getOccurredAt());
		patient.setCallOutcome(savedCall.getCallOutcome());
		patientRepository.save(patient);

		List<SyncStep> steps = new ArrayList<>();
		steps.add(new SyncStep(book ? "Patient created in Utessa" : "Lead saved in Utessa",
				"Matched on name, date of birth and phone: none found", "DONE"));
		if (book) {
			boolean ehrLive = integrationService.isEhrConnected();
			String state = ehrLive ? "QUEUED" : "WAITING";
			String ehrDetail = ehrLive ? "Queued — sends automatically" : "Queued until the EHR integration is connected";
			steps.add(new SyncStep("Patient created in EHR", ehrDetail, state));
			steps.add(new SyncStep("Appointment booked in EHR", ehrDetail, state));
			steps.add(new SyncStep("Call notes filed in EHR", "As a telephone encounter on the new chart · " + ehrDetail, state));
			steps.add(new SyncStep(Boolean.TRUE.equals(r.smsConsent()) ? "Confirmation and intake forms texted" : "No text sent",
					Boolean.TRUE.equals(r.smsConsent())
							? "Sends once the phone system is connected"
							: "Caller did not agree to texts", Boolean.TRUE.equals(r.smsConsent()) ? "WAITING" : "SKIPPED"));
		} else if (r.followUpOn() != null) {
			steps.add(new SyncStep("Follow-up task created", "Due " + r.followUpOn(), "DONE"));
		}
		Patient fresh = patientRepository.findById(patient.getId()).orElse(patient);
		return new IntakeResponse(patientService.toResponse(fresh), appointment, CrmMapper.activity(savedCall, fresh), steps);
	}

	// ── Providers & openings ────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<ProviderOption> providers(UserPrincipal principal) {
		support.requireAccess(principal);
		Map<String, ProviderOption> out = new LinkedHashMap<>();
		for (StaffMember s : staffRepository.findByOrganizationIdAndProviderOrderByLastNameAscFirstNameAsc(support.orgId(), true)) {
			if (s.getStatus() == EmploymentStatus.INACTIVE || s.getStatus() == EmploymentStatus.SUSPENDED) {
				continue;
			}
			String name = providerName(s);
			out.putIfAbsent(name.toLowerCase(), new ProviderOption(name, s.getSpecialty(), s.getLocation()));
		}
		appointmentRepository.findByOrganizationIdOrderByStartsAtAsc(support.orgId()).stream()
				.map(Appointment::getProviderName)
				.filter(Objects::nonNull)
				.forEach(n -> out.putIfAbsent(n.toLowerCase(), new ProviderOption(n, null, null)));
		return new ArrayList<>(out.values());
	}

	@Transactional(readOnly = true)
	public List<OpeningDay> openings(UserPrincipal principal, String provider, LocalDate from, int duration, int days) {
		support.requireAccess(principal);
		ZoneId zone = ZoneId.systemDefault();
		LocalDate day = from == null || from.isBefore(LocalDate.now(zone)) ? LocalDate.now(zone) : from;
		int minutes = Math.max(10, Math.min(duration, 180));
		String providerKey = provider == null ? null : provider.trim().toLowerCase();
		List<OpeningDay> result = new ArrayList<>();
		Instant now = Instant.now();
		int guard = 0;
		while (result.size() < Math.max(1, Math.min(days, 7)) && guard++ < 21) {
			if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
				Instant dayStart = day.atStartOfDay(zone).toInstant();
				List<Appointment> booked = appointmentRepository
						.findByOrganizationIdAndStartsAtBetweenOrderByStartsAtAsc(support.orgId(), dayStart, dayStart.plusSeconds(86400))
						.stream()
						.filter(Appointment::isActive)
						.filter(a -> providerKey == null || (a.getProviderName() != null && a.getProviderName().equalsIgnoreCase(providerKey)))
						.toList();
				List<Instant> slots = new ArrayList<>();
				ZonedDateTime t = day.atTime(DAY_START_END.get(0)).atZone(zone);
				ZonedDateTime end = day.atTime(DAY_START_END.get(1)).atZone(zone);
				while (!t.plusMinutes(minutes).isAfter(end)) {
					ZonedDateTime slotEnd = t.plusMinutes(minutes);
					boolean lunch = t.toLocalTime().isBefore(LocalTime.of(13, 0)) && slotEnd.toLocalTime().isAfter(LocalTime.of(12, 0));
					Instant s = t.toInstant();
					Instant e = slotEnd.toInstant();
					boolean clash = booked.stream().anyMatch(a -> a.getStartsAt().isBefore(e)
							&& a.getStartsAt().plusSeconds(a.getDurationMinutes() * 60L).isAfter(s));
					if (!lunch && !clash && s.isAfter(now.plusSeconds(15 * 60))) {
						slots.add(s);
					}
					t = slotEnd;
				}
				if (!slots.isEmpty()) {
					result.add(new OpeningDay(day, slots));
				}
			}
			day = day.plusDays(1);
		}
		return result;
	}

	// ── Call analytics ──────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public CallAnalytics analytics(UserPrincipal principal, LocalDate from, LocalDate to, UUID userId, String line, String tz) {
		support.requireAccess(principal);
		ZoneId zone = TaskService.zone(tz);
		LocalDate end = to == null ? LocalDate.now(zone) : to;
		LocalDate start = from == null ? end.minusDays(13) : from;
		if (end.isBefore(start)) {
			throw ApiException.badRequest("The end date must be on or after the start date.");
		}
		Instant s = start.atStartOfDay(zone).toInstant();
		Instant e = end.plusDays(1).atStartOfDay(zone).toInstant();
		UUID orgId = support.orgId();

		List<Activity> all = activityRepository.findByOrganizationIdOrderByOccurredAtDesc(orgId, PageRequest.of(0, 20000));
		List<Activity> calls = all.stream()
				.filter(a -> CALLS.contains(a.getActivityType()))
				.filter(a -> !a.getOccurredAt().isBefore(s) && a.getOccurredAt().isBefore(e))
				.filter(a -> userId == null || userId.equals(a.getActorUserId()))
				.filter(a -> line == null || line.isBlank() || line.equalsIgnoreCase(a.getPhoneLine()))
				.toList();
		List<Activity> inbound = calls.stream().filter(a -> a.getActivityType() == ActivityType.CALL_INBOUND).toList();
		long missed = inbound.stream().filter(CallFlowService::isMissed).count();
		long answered = inbound.size() - missed;
		List<Activity> handled = calls.stream().filter(a -> !isMissed(a)).toList();
		long avgDur = (long) handled.stream().filter(a -> a.getDurationSeconds() != null)
				.mapToInt(Activity::getDurationSeconds).average().orElse(0);
		long documented = handled.stream().filter(CallFlowService::isDocumented).count();
		long booked = calls.stream().filter(a -> OUTCOME_NEW_PATIENT.equals(a.getCallOutcome())).count();
		long leads = calls.stream().filter(a -> OUTCOME_SAVED_LEAD.equals(a.getCallOutcome())).count();
		long linked = all.stream().filter(a -> a.getActivityType() == ActivityType.SYSTEM)
				.filter(a -> a.getSubject() != null && a.getSubject().startsWith("Linked "))
				.filter(a -> !a.getOccurredAt().isBefore(s) && a.getOccurredAt().isBefore(e))
				.count();

		List<DayPoint> perDay = new ArrayList<>();
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			LocalDate day = d;
			List<Activity> dayIn = inbound.stream().filter(a -> LocalDate.ofInstant(a.getOccurredAt(), zone).equals(day)).toList();
			long m = dayIn.stream().filter(CallFlowService::isMissed).count();
			perDay.add(new DayPoint(day, dayIn.size(), dayIn.size() - m, m));
		}

		long newCalls = inbound.stream().filter(a -> "NEW_CALLER".equals(a.getCallerType())).count();
		long newAnswered = inbound.stream().filter(a -> "NEW_CALLER".equals(a.getCallerType()) && !isMissed(a)).count();
		long newSaved = inbound.stream().filter(a -> "NEW_CALLER".equals(a.getCallerType())
				&& (OUTCOME_NEW_PATIENT.equals(a.getCallOutcome()) || OUTCOME_SAVED_LEAD.equals(a.getCallOutcome()))).count();
		List<FunnelStep> funnel = List.of(
				new FunnelStep("Calls from new numbers", newCalls, 100),
				new FunnelStep("Answered", newAnswered, pct(newAnswered, newCalls)),
				new FunnelStep("Saved as patient or lead", newSaved, pct(newSaved, newAnswered)),
				new FunnelStep("New patient booked", booked, pct(booked, newSaved)));

		List<Activity> existing = handled.stream()
				.filter(a -> "PATIENT".equals(a.getCallerType()) || "LEAD".equals(a.getCallerType()))
				.filter(a -> a.getCallReason() != null)
				.toList();
		List<Count> reasons = existing.stream()
				.collect(Collectors.groupingBy(Activity::getCallReason, LinkedHashMap::new, Collectors.counting()))
				.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.map(en -> new Count(en.getKey(), en.getValue(), pct(en.getValue(), existing.size())))
				.toList();

		int[][] heat = new int[7][13];
		for (Activity a : inbound) {
			ZonedDateTime z = a.getOccurredAt().atZone(zone);
			int h = z.getHour() - 7;
			if (h >= 0 && h < 13) {
				heat[z.getDayOfWeek().getValue() - 1][h]++;
			}
		}

		Map<String, List<Activity>> byActor = calls.stream()
				.collect(Collectors.groupingBy(a -> a.getActorName() == null ? "Unknown" : a.getActorName()));
		List<StaffRow> staff = byActor.entrySet().stream().map(en -> {
			List<Activity> list = en.getValue();
			List<Activity> h = list.stream().filter(a -> !isMissed(a)).toList();
			long avg = (long) h.stream().filter(a -> a.getDurationSeconds() != null).mapToInt(Activity::getDurationSeconds).average().orElse(0);
			long doc = h.stream().filter(CallFlowService::isDocumented).count();
			long nb = list.stream().filter(a -> OUTCOME_NEW_PATIENT.equals(a.getCallOutcome())).count();
			return new StaffRow(en.getKey(), list.size(), avg, pct(doc, h.size()), nb);
		}).sorted(Comparator.comparingLong(StaffRow::calls).reversed()).toList();

		List<EhrLogRow> log = new ArrayList<>();
		Set<UUID> seen = new LinkedHashSet<>();
		Map<UUID, Patient> patients = support.patients(all.stream().map(Activity::getPatientId).toList());
		for (Activity a : all) {
			if (log.size() >= 10) {
				break;
			}
			if (!IntegrationService.SYNC_NOT_LINKED.equals(a.getEhrSyncStatus())) {
				Patient p = patients.get(a.getPatientId());
				String what = OUTCOME_NEW_PATIENT.equals(a.getCallOutcome())
						? "New patient + appointment · " + (p == null ? "—" : p.getFullName())
						: "Call note · " + (p == null ? a.getCallerName() : p.getFullName());
				if (seen.add(a.getId())) {
					log.add(new EhrLogRow(what, a.getOccurredAt(), a.getEhrSyncStatus()));
				}
			}
		}

		return new CallAnalytics(inbound.size(), calls.size() - inbound.size(), round1(pctD(answered, inbound.size())),
				avgDur, round1(pctD(documented, handled.size())), booked, leads, linked, perDay, funnel, reasons, heat, staff, log);
	}

	// ── helpers ─────────────────────────────────────────────────────────

	static boolean isMissed(Activity a) {
		String o = a.getCallOutcome();
		return o != null && (o.equalsIgnoreCase(OUTCOME_MISSED) || o.equalsIgnoreCase("Abandoned")
				|| (a.getActivityType() == ActivityType.CALL_INBOUND && o.equalsIgnoreCase("No answer")));
	}

	private static boolean isDocumented(Activity a) {
		return a.getCallReason() != null || (a.getBody() != null && !a.getBody().isBlank());
	}

	public static String providerName(StaffMember s) {
		String first = s.getPreferredName() != null && !s.getPreferredName().isBlank() ? s.getPreferredName() : s.getFirstName();
		String base = CrmSupport.joinName(first, s.getLastName());
		String d = s.getDesignation();
		if (d != null && (d.equalsIgnoreCase("MD") || d.equalsIgnoreCase("DO") || d.equalsIgnoreCase("DDS")
				|| d.equalsIgnoreCase("DPM") || d.equalsIgnoreCase("DC") || d.equalsIgnoreCase("PhD"))) {
			return "Dr. " + base;
		}
		return d == null || d.isBlank() ? base : base + ", " + d;
	}

	private static int pct(long part, long whole) {
		return whole == 0 ? 0 : (int) Math.round(part * 100.0 / whole);
	}

	private static double pctD(long part, long whole) {
		return whole == 0 ? 0 : part * 100.0 / whole;
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static String lower(String v) {
		String c = CrmSupport.clean(v);
		return c == null ? null : c.toLowerCase();
	}
}
