package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityRequest;
import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.BulkRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.BulkResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.ConvertRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.DuplicateMatch;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.StatusChangeRequest;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.ReferralPartner;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.persistence.criteria.Predicate;

/** Patient / lead records: CRUD, lifecycle, duplicate detection, timeline. */
@Service
public class PatientService {

	private static final int MAX_LIST = 1000;

	private final PatientRepository repository;
	private final ActivityRepository activityRepository;
	private final TaskService taskService;
	private final IntegrationService integrationService;
	private final CrmSupport support;

	public PatientService(
			PatientRepository repository,
			ActivityRepository activityRepository,
			TaskService taskService,
			IntegrationService integrationService,
			CrmSupport support
	) {
		this.repository = repository;
		this.activityRepository = activityRepository;
		this.taskService = taskService;
		this.integrationService = integrationService;
		this.support = support;
	}

	// ── queries ─────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<PatientResponse> list(UserPrincipal principal, PatientFilter filter) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		Specification<Patient> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("organizationId"), orgId));
			if (filter.lifecycle() != null) {
				predicates.add(cb.equal(root.get("lifecycleStage"), filter.lifecycle()));
			}
			if (filter.statuses() != null && !filter.statuses().isEmpty()) {
				predicates.add(root.get("leadStatus").in(filter.statuses()));
			}
			if (filter.priority() != null) {
				predicates.add(cb.equal(root.get("priority"), filter.priority()));
			}
			if (filter.ownerUserId() != null) {
				predicates.add(cb.equal(root.get("ownerUserId"), filter.ownerUserId()));
			}
			if (filter.referralPartnerId() != null) {
				predicates.add(cb.equal(root.get("referralPartnerId"), filter.referralPartnerId()));
			}
			if (filter.source() != null && !filter.source().isBlank()) {
				predicates.add(cb.equal(root.get("source"), filter.source().trim()));
			}
			if (filter.followUp() != null) {
				LocalDate today = LocalDate.now();
				switch (filter.followUp().toLowerCase()) {
					case "overdue" -> predicates.add(cb.lessThan(root.<LocalDate>get("nextFollowUp"), today));
					case "today" -> predicates.add(cb.equal(root.get("nextFollowUp"), today));
					case "none" -> predicates.add(cb.isNull(root.get("nextFollowUp")));
					default -> {
					}
				}
			}
			if (filter.q() != null && !filter.q().isBlank()) {
				String like = "%" + filter.q().trim().toLowerCase() + "%";
				List<Predicate> any = new ArrayList<>();
				any.add(cb.like(cb.lower(root.<String>get("fullName")), like));
				any.add(cb.like(cb.lower(cb.coalesce(root.<String>get("email"), "")), like));
				any.add(cb.like(cb.lower(cb.coalesce(root.<String>get("mrn"), "")), like));
				any.add(cb.like(cb.lower(cb.coalesce(root.<String>get("insurancePayer"), "")), like));
				String digits = CrmSupport.normalizePhone(filter.q());
				if (digits != null && digits.length() >= 3) {
					any.add(cb.like(cb.coalesce(root.<String>get("phoneNormalized"), ""), "%" + digits + "%"));
					any.add(cb.like(cb.coalesce(root.<String>get("altPhoneNormalized"), ""), "%" + digits + "%"));
				}
				predicates.add(cb.or(any.toArray(Predicate[]::new)));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		List<Patient> rows = repository.findAll(spec, PageRequest.of(0, MAX_LIST, Sort.by(Sort.Direction.DESC, "createdAt")))
				.getContent();
		Map<UUID, ReferralPartner> partners = support.partners();
		return rows.stream().map(p -> CrmMapper.patient(p, partners.get(p.getReferralPartnerId()))).toList();
	}

	@Transactional(readOnly = true)
	public PatientResponse get(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		return toResponse(support.requirePatient(id));
	}

	@Transactional(readOnly = true)
	public List<DuplicateMatch> duplicates(UserPrincipal principal, String phone, String email, UUID excludeId) {
		support.requireAccess(principal);
		return findDuplicates(phone, email, excludeId);
	}

	@Transactional(readOnly = true)
	public List<ActivityResponse> timeline(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		Patient patient = support.requirePatient(id);
		return activityRepository.findByOrganizationIdAndPatientIdOrderByOccurredAtDesc(support.orgId(), id)
				.stream()
				.map(a -> CrmMapper.activity(a, patient))
				.toList();
	}

	// ── commands ────────────────────────────────────────────────────────

	@Transactional
	public PatientResponse create(UserPrincipal principal, PatientRequest request, boolean allowDuplicate) {
		support.requireAccess(principal);
		return toResponse(createRecord(principal, request, allowDuplicate, true));
	}

	/**
	 * Creates a patient/lead. When {@code speedToLead} is set, a brand-new lead without a
	 * follow-up date gets a first-contact task (15 min for urgent/high, 1 hour otherwise).
	 */
	@Transactional
	public Patient createRecord(UserPrincipal principal, PatientRequest request, boolean allowDuplicate, boolean speedToLead) {
		if (!allowDuplicate) {
			List<DuplicateMatch> dupes = findDuplicates(request.phone(), request.email(), null);
			if (!dupes.isEmpty()) {
				DuplicateMatch first = dupes.get(0);
				throw ApiException.conflict("Possible duplicate: " + first.fullName() + " already uses this "
						+ first.matchedOn() + ". Open the existing record or save anyway.");
			}
		}
		Patient patient = new Patient();
		patient.setCreatedByUserId(principal.userId());
		apply(patient, request);
		// "Converted" means they are a patient: keep the record type in step with the status.
		if (patient.getLeadStatus() == LeadStatus.CONVERTED) {
			patient.setLifecycleStage(Lifecycle.PATIENT);
		}
		if (patient.getOwnerUserId() == null) {
			patient.setOwnerUserId(principal.userId());
			patient.setOwnerName(support.actorName(principal));
		}
		if (patient.getLifecycleStage() == Lifecycle.PATIENT) {
			patient.setLeadStatus(LeadStatus.CONVERTED);
			patient.setConvertedAt(Instant.now());
			if (patient.getEhrPatientId() == null && integrationService.autoCreateEhrPatients()) {
				patient.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
			}
		}
		Patient saved = repository.save(patient);
		String label = saved.isLead() ? "Lead created" : "Patient added";
		support.log(principal, ActivityType.SYSTEM, saved.getId(), null, null,
				label + (saved.getSource() == null ? "" : " · source: " + saved.getSource()),
				saved.getReasonForVisit());

		if (request.nextFollowUp() != null) {
			taskService.createSystemTask(principal, saved.getId(), null, null,
					"Follow up with " + saved.getFullName(), "CALL", saved.getPriority(),
					atNineAm(request.nextFollowUp()), saved.getOwnerUserId());
		} else if (speedToLead && saved.isLead() && saved.getLeadStatus() == LeadStatus.NEW) {
			long minutes = saved.getPriority() == Priority.URGENT || saved.getPriority() == Priority.HIGH ? 15 : 60;
			taskService.createSystemTask(principal, saved.getId(), null, null,
					TaskService.FIRST_CONTACT_PREFIX + saved.getFullName(), "CALL", saved.getPriority(),
					Instant.now().plus(minutes, ChronoUnit.MINUTES), saved.getOwnerUserId());
		}
		return repository.findById(saved.getId()).orElse(saved);
	}

	@Transactional
	public PatientResponse update(UserPrincipal principal, UUID id, PatientRequest request) {
		support.requireAccess(principal);
		Patient patient = support.requirePatient(id);
		LeadStatus previousStatus = patient.getLeadStatus();
		Lifecycle previousLifecycle = patient.getLifecycleStage();
		UUID previousOwner = patient.getOwnerUserId();
		LocalDate previousFollowUp = patient.getNextFollowUp();

		apply(patient, request);
		patient.setNextFollowUp(previousFollowUp);
		// Setting the status to Converted moves a lead to Patients, same as the status shortcut and Convert.
		if (previousStatus != LeadStatus.CONVERTED && patient.getLeadStatus() == LeadStatus.CONVERTED) {
			patient.setLifecycleStage(Lifecycle.PATIENT);
		}
		if (previousLifecycle != Lifecycle.PATIENT && patient.getLifecycleStage() == Lifecycle.PATIENT) {
			markConverted(patient);
		}
		Patient saved = repository.save(patient);

		if (previousStatus != saved.getLeadStatus()) {
			support.log(principal, ActivityType.STATUS_CHANGE, saved.getId(), null, null,
					"Status: " + CrmSupport.humanize(previousStatus) + " → " + CrmSupport.humanize(saved.getLeadStatus()),
					saved.getLostReason());
			afterStatusChange(principal, saved);
		}
		if (!Objects.equals(previousOwner, saved.getOwnerUserId())) {
			support.log(principal, ActivityType.SYSTEM, saved.getId(), null, null,
					"Assigned to " + (saved.getOwnerName() == null ? "nobody" : saved.getOwnerName()), null);
		}
		if (request.nextFollowUp() != null && !request.nextFollowUp().equals(previousFollowUp)) {
			taskService.createSystemTask(principal, saved.getId(), null, null,
					"Follow up with " + saved.getFullName(), "CALL", saved.getPriority(),
					atNineAm(request.nextFollowUp()), saved.getOwnerUserId());
		}
		return toResponse(repository.findById(saved.getId()).orElse(saved));
	}

	@Transactional
	public PatientResponse changeStatus(UserPrincipal principal, UUID id, StatusChangeRequest request) {
		support.requireAccess(principal);
		Patient patient = support.requirePatient(id);
		LeadStatus previous = patient.getLeadStatus();
		if (previous == request.leadStatus()) {
			return toResponse(patient);
		}
		patient.setLeadStatus(request.leadStatus());
		if (request.leadStatus() == LeadStatus.LOST || request.leadStatus() == LeadStatus.NOT_QUALIFIED) {
			patient.setLostReason(CrmSupport.clean(request.lostReason()));
		}
		if (request.leadStatus() == LeadStatus.CONVERTED && patient.getLifecycleStage() != Lifecycle.PATIENT) {
			patient.setLifecycleStage(Lifecycle.PATIENT);
			markConverted(patient);
		}
		repository.save(patient);
		support.log(principal, ActivityType.STATUS_CHANGE, id, null, null,
				"Status: " + CrmSupport.humanize(previous) + " → " + CrmSupport.humanize(request.leadStatus()),
				joinLines(CrmSupport.clean(request.lostReason()), CrmSupport.clean(request.note())));
		afterStatusChange(principal, patient);
		return toResponse(repository.findById(id).orElse(patient));
	}

	@Transactional
	public PatientResponse convert(UserPrincipal principal, UUID id, ConvertRequest request) {
		support.requireAccess(principal);
		Patient patient = support.requirePatient(id);
		if (patient.getLifecycleStage() == Lifecycle.PATIENT) {
			throw ApiException.badRequest("This record is already a patient.");
		}
		LeadStatus previous = patient.getLeadStatus();
		patient.setLifecycleStage(Lifecycle.PATIENT);
		patient.setLeadStatus(LeadStatus.CONVERTED);
		String mrn = request == null ? null : CrmSupport.clean(request.mrn());
		if (mrn != null) {
			patient.setMrn(mrn);
		}
		markConverted(patient);
		if (request != null && Boolean.TRUE.equals(request.pushToEhr()) && patient.getEhrPatientId() == null) {
			patient.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
		}
		repository.save(patient);
		support.log(principal, ActivityType.STATUS_CHANGE, id, null, null,
				"Converted to patient (was " + CrmSupport.humanize(previous) + ")",
				request == null ? null : CrmSupport.clean(request.note()));
		if (IntegrationService.SYNC_PENDING.equals(patient.getEhrSyncStatus())) {
			support.log(principal, ActivityType.SYSTEM, id, null, null,
					"Queued for EHR patient creation", "Will be created in the EHR once the integration is live.");
		}
		return toResponse(patient);
	}

	@Transactional
	public PatientResponse requestEhrSync(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		Patient patient = support.requirePatient(id);
		if (patient.getEhrPatientId() != null) {
			throw ApiException.badRequest("This record is already linked to the EHR.");
		}
		patient.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
		repository.save(patient);
		support.log(principal, ActivityType.SYSTEM, id, null, null, "Queued for EHR sync", null);
		return toResponse(patient);
	}

	@Transactional
	public ActivityResponse addActivity(UserPrincipal principal, UUID id, ActivityRequest request) {
		support.requireAccess(principal);
		Patient patient = support.requirePatient(id);
		ActivityType type = request.type();
		if (type == ActivityType.STATUS_CHANGE || type == ActivityType.SYSTEM) {
			throw ApiException.badRequest("This activity type is recorded automatically.");
		}
		Activity activity = new Activity();
		activity.setPatientId(id);
		activity.setOpportunityId(request.opportunityId());
		activity.setReferralId(request.referralId());
		activity.setActivityType(type);
		String subject = CrmSupport.clean(request.subject());
		activity.setSubject(CrmSupport.truncate(subject == null ? defaultSubject(type, request.callOutcome()) : subject, 255));
		activity.setBody(CrmSupport.clean(request.body()));
		activity.setPhone(CrmSupport.clean(request.phone()) == null ? patient.getPhone() : request.phone().trim());
		activity.setCallOutcome(CrmSupport.clean(request.callOutcome()));
		activity.setDurationSeconds(request.durationSeconds());
		activity.setOccurredAt(request.occurredAt() == null ? Instant.now() : request.occurredAt());
		activity.setActorUserId(principal.userId());
		activity.setActorName(support.actorName(principal));
		Activity saved = support.save(activity);
		if (type != ActivityType.NOTE) {
			applyContact(principal, patient, type, activity.getCallOutcome(), saved.getOccurredAt());
		}
		return CrmMapper.activity(saved, patient);
	}

	@Transactional
	public BulkResponse bulk(UserPrincipal principal, BulkRequest request) {
		support.requireAccess(principal);
		String action = request.action().toUpperCase();
		if ("DELETE".equals(action)) {
			support.requireAdmin(principal);
		}
		List<Patient> patients = repository.findByOrganizationIdAndIdIn(support.orgId(), request.ids());
		String ownerName = null;
		UUID ownerId = null;
		if ("ASSIGN".equals(action)) {
			ownerId = request.value() == null || request.value().isBlank() ? null : UUID.fromString(request.value());
			ownerName = support.memberName(ownerId);
		}
		for (Patient patient : patients) {
			switch (action) {
				case "ASSIGN" -> {
					patient.setOwnerUserId(ownerId);
					patient.setOwnerName(ownerName);
					support.log(principal, ActivityType.SYSTEM, patient.getId(), null, null,
							"Assigned to " + (ownerName == null ? "nobody" : ownerName), "Bulk update");
				}
				case "PRIORITY" -> patient.setPriority(parse(Priority.class, request.value()));
				case "STATUS" -> {
					LeadStatus next = parse(LeadStatus.class, request.value());
					if (next != patient.getLeadStatus()) {
						support.log(principal, ActivityType.STATUS_CHANGE, patient.getId(), null, null,
								"Status: " + CrmSupport.humanize(patient.getLeadStatus()) + " → " + CrmSupport.humanize(next),
								"Bulk update");
						patient.setLeadStatus(next);
						if (next == LeadStatus.CONVERTED && patient.getLifecycleStage() != Lifecycle.PATIENT) {
							patient.setLifecycleStage(Lifecycle.PATIENT);
							markConverted(patient);
						}
					}
				}
				case "DELETE" -> {
				}
				default -> throw ApiException.badRequest("Unknown bulk action.");
			}
		}
		if ("DELETE".equals(action)) {
			repository.deleteAll(patients);
		} else {
			repository.saveAll(patients);
			if ("STATUS".equals(action)) {
				patients.forEach(p -> afterStatusChange(principal, p));
			}
		}
		return new BulkResponse(patients.size());
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		support.requireAdmin(principal);
		repository.delete(support.requirePatient(id));
	}

	// ── shared with call desk / appointments ────────────────────────────

	/** Updates last-contact fields and nudges lead status forward after a call / SMS / email. */
	public void applyContact(UserPrincipal principal, Patient patient, ActivityType type, String outcome, Instant at) {
		patient.setLastContactAt(at == null ? Instant.now() : at);
		if (type == ActivityType.CALL_INBOUND || type == ActivityType.CALL_OUTBOUND) {
			patient.setCallOutcome(outcome);
		}
		if (patient.isLead()) {
			LeadStatus before = patient.getLeadStatus();
			boolean reached = type == ActivityType.CALL_INBOUND || type == ActivityType.MEETING
					|| (outcome != null && (outcome.equalsIgnoreCase("Reached") || outcome.equalsIgnoreCase("Scheduled")
							|| outcome.equalsIgnoreCase("Callback requested")));
			if (before == LeadStatus.NEW || before == LeadStatus.ATTEMPTING_CONTACT) {
				LeadStatus next = reached ? LeadStatus.CONTACTED : LeadStatus.ATTEMPTING_CONTACT;
				if (next != before) {
					patient.setLeadStatus(next);
					support.log(principal, ActivityType.STATUS_CHANGE, patient.getId(), null, null,
							"Status: " + CrmSupport.humanize(before) + " → " + CrmSupport.humanize(next), null);
				}
			}
		}
		repository.save(patient);
		taskService.completeFirstContact(principal, patient.getId());
	}

	/** Moves a lead to APPOINTMENT_SCHEDULED / SHOWED when the visit pipeline advances. */
	public void advanceForAppointment(UserPrincipal principal, Patient patient, LeadStatus target) {
		if (!patient.isLead()) {
			return;
		}
		LeadStatus before = patient.getLeadStatus();
		if (before == LeadStatus.LOST || before == LeadStatus.NOT_QUALIFIED || before == LeadStatus.CONVERTED
				|| before.ordinal() >= target.ordinal()) {
			return;
		}
		patient.setLeadStatus(target);
		repository.save(patient);
		support.log(principal, ActivityType.STATUS_CHANGE, patient.getId(), null, null,
				"Status: " + CrmSupport.humanize(before) + " → " + CrmSupport.humanize(target), null);
	}

	public PatientResponse toResponse(Patient patient) {
		ReferralPartner partner = patient.getReferralPartnerId() == null
				? null
				: support.partners().get(patient.getReferralPartnerId());
		return CrmMapper.patient(patient, partner);
	}

	// ── internals ───────────────────────────────────────────────────────

	private List<DuplicateMatch> findDuplicates(String phone, String email, UUID excludeId) {
		UUID orgId = support.orgId();
		Map<UUID, DuplicateMatch> matches = new LinkedHashMap<>();
		String normalized = CrmSupport.normalizePhone(phone);
		if (normalized != null && normalized.length() >= 7) {
			for (Patient p : repository.findByPhone(orgId, normalized)) {
				matches.putIfAbsent(p.getId(), match(p, "phone number"));
			}
		}
		String cleanEmail = CrmSupport.clean(email);
		if (cleanEmail != null) {
			for (Patient p : repository.findByEmail(orgId, cleanEmail)) {
				matches.putIfAbsent(p.getId(), match(p, "email address"));
			}
		}
		if (excludeId != null) {
			matches.remove(excludeId);
		}
		return new ArrayList<>(matches.values());
	}

	private DuplicateMatch match(Patient p, String on) {
		return new DuplicateMatch(p.getId(), p.getFullName(), p.getPhone(), p.getEmail(),
				p.getLifecycleStage(), p.getLeadStatus(), on);
	}

	private void afterStatusChange(UserPrincipal principal, Patient patient) {
		if (patient.getLeadStatus() == LeadStatus.LOST || patient.getLeadStatus() == LeadStatus.NOT_QUALIFIED) {
			int cancelled = taskService.cancelOpenTasks(patient.getId(),
					"Cancelled automatically: lead marked " + CrmSupport.humanize(patient.getLeadStatus()).toLowerCase() + ".");
			if (cancelled > 0) {
				support.log(principal, ActivityType.SYSTEM, patient.getId(), null, null,
						cancelled + " open follow-up" + (cancelled == 1 ? "" : "s") + " cancelled", null);
			}
		}
	}

	private void markConverted(Patient patient) {
		patient.setLeadStatus(LeadStatus.CONVERTED);
		if (patient.getConvertedAt() == null) {
			patient.setConvertedAt(Instant.now());
		}
		if (patient.getEhrPatientId() == null && integrationService.autoCreateEhrPatients()) {
			patient.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
		}
	}

	private void apply(Patient p, PatientRequest r) {
		p.setFirstName(r.firstName().trim());
		p.setLastName(CrmSupport.clean(r.lastName()));
		p.setFullName(CrmSupport.joinName(r.firstName(), r.lastName()));
		p.setPreferredName(CrmSupport.clean(r.preferredName()));
		p.setDateOfBirth(r.dateOfBirth());
		if (r.dateOfBirth() != null && r.dateOfBirth().isAfter(LocalDate.now())) {
			throw ApiException.badRequest("Date of birth cannot be in the future.");
		}
		p.setGender(CrmSupport.clean(r.gender()));
		p.setPhone(CrmSupport.clean(r.phone()));
		p.setPhoneNormalized(CrmSupport.normalizePhone(r.phone()));
		p.setAltPhone(CrmSupport.clean(r.altPhone()));
		p.setAltPhoneNormalized(CrmSupport.normalizePhone(r.altPhone()));
		p.setEmail(CrmSupport.clean(r.email()));
		p.setPreferredContact(CrmSupport.clean(r.preferredContact()));
		p.setPreferredLanguage(CrmSupport.clean(r.preferredLanguage()));
		p.setBestTimeToCall(CrmSupport.clean(r.bestTimeToCall()));
		p.setConsentCall(r.consentCall() == null || r.consentCall());
		p.setConsentSms(Boolean.TRUE.equals(r.consentSms()));
		p.setConsentEmail(Boolean.TRUE.equals(r.consentEmail()));
		p.setDoNotContact(Boolean.TRUE.equals(r.doNotContact()));
		p.setStreet(CrmSupport.clean(r.street()));
		p.setCity(CrmSupport.clean(r.city()));
		p.setState(CrmSupport.clean(r.state()));
		p.setZip(CrmSupport.clean(r.zip()));
		p.setSource(CrmSupport.clean(r.source()));
		p.setSourceDetail(CrmSupport.clean(r.sourceDetail()));
		p.setCampaign(CrmSupport.clean(r.campaign()));
		if (r.referralPartnerId() != null) {
			support.requirePartner(r.referralPartnerId());
		}
		p.setReferralPartnerId(r.referralPartnerId());
		p.setInterestedService(CrmSupport.clean(r.interestedService()));
		p.setPreferredProvider(CrmSupport.clean(r.preferredProvider()));
		p.setPreferredLocation(CrmSupport.clean(r.preferredLocation()));
		p.setReasonForVisit(CrmSupport.clean(r.reasonForVisit()));
		p.setInsurancePayer(CrmSupport.clean(r.insurancePayer()));
		p.setInsurancePlan(CrmSupport.clean(r.insurancePlan()));
		p.setInsuranceMemberId(CrmSupport.clean(r.insuranceMemberId()));
		p.setInsuranceVerified(Boolean.TRUE.equals(r.insuranceVerified()));
		if (r.lifecycleStage() != null) {
			p.setLifecycleStage(r.lifecycleStage());
		}
		if (r.leadStatus() != null) {
			p.setLeadStatus(r.leadStatus());
		}
		p.setPriority(r.priority() == null ? Priority.MEDIUM : r.priority());
		if (r.ownerUserId() != null || p.getId() != null) {
			p.setOwnerUserId(r.ownerUserId());
			p.setOwnerName(support.memberName(r.ownerUserId()));
		}
		p.setNextFollowUp(r.nextFollowUp());
		p.setLostReason(CrmSupport.clean(r.lostReason()));
		p.setMrn(CrmSupport.clean(r.mrn()));
		p.setTags(CrmSupport.clean(r.tags()));
		p.setNotes(CrmSupport.clean(r.notes()));
		String coverage = CrmSupport.clean(r.coverageType());
		p.setCoverageType(coverage == null ? "INSURANCE" : coverage.toUpperCase());
		p.setInsuranceGroup(CrmSupport.clean(r.insuranceGroup()));
		p.setPolicyHolder(CrmSupport.clean(r.policyHolder()));
		p.setSmsReminders(Boolean.TRUE.equals(r.smsReminders()));
	}

	private static String defaultSubject(ActivityType type, String outcome) {
		String base = switch (type) {
			case CALL_INBOUND -> "Inbound call";
			case CALL_OUTBOUND -> "Outbound call";
			case SMS -> "Text message";
			case EMAIL -> "Email";
			case MEETING -> "Meeting";
			default -> "Note";
		};
		return outcome == null || outcome.isBlank() ? base : base + " · " + outcome.trim();
	}

	private static String joinLines(String a, String b) {
		if (a == null) {
			return b;
		}
		return b == null ? a : a + "\n" + b;
	}

	private static Instant atNineAm(LocalDate date) {
		return date.atTime(LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant();
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value) {
		try {
			return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase());
		} catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("Invalid value: " + value);
		}
	}

	public record PatientFilter(
			Lifecycle lifecycle,
			List<LeadStatus> statuses,
			Priority priority,
			UUID ownerUserId,
			UUID referralPartnerId,
			String source,
			String followUp,
			String q
	) {
	}
}
