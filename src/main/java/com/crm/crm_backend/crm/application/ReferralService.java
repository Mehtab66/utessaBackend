package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientRequest;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.PartnerRequest;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.PartnerResponse;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.ReferralRequest;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.ReferralResponse;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.ReferralStatusRequest;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.Referral;
import com.crm.crm_backend.crm.domain.ReferralPartner;
import com.crm.crm_backend.crm.domain.ReferralStatus;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.ReferralPartnerRepository;
import com.crm.crm_backend.crm.infrastructure.ReferralRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * Referral management: referring partners (physicians, practices, facilities) and the
 * inbound referral work queue (review -> insurance -> authorization -> scheduled -> seen).
 */
@Service
public class ReferralService {

	private final ReferralRepository repository;
	private final ReferralPartnerRepository partnerRepository;
	private final PatientRepository patientRepository;
	private final ActivityRepository activityRepository;
	private final PatientService patientService;
	private final TaskService taskService;
	private final CrmSupport support;

	public ReferralService(
			ReferralRepository repository,
			ReferralPartnerRepository partnerRepository,
			PatientRepository patientRepository,
			ActivityRepository activityRepository,
			PatientService patientService,
			TaskService taskService,
			CrmSupport support
	) {
		this.repository = repository;
		this.partnerRepository = partnerRepository;
		this.patientRepository = patientRepository;
		this.activityRepository = activityRepository;
		this.patientService = patientService;
		this.taskService = taskService;
		this.support = support;
	}

	// ── partners ────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<PartnerResponse> partners(UserPrincipal principal) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		List<Referral> referrals = repository.findByOrganizationIdOrderByReceivedOnDescCreatedAtDesc(orgId);
		Map<UUID, List<Referral>> byPartner = referrals.stream()
				.filter(r -> r.getReferralPartnerId() != null)
				.collect(Collectors.groupingBy(Referral::getReferralPartnerId));
		Map<UUID, Long> patientsByPartner = patientRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
				.filter(p -> p.getReferralPartnerId() != null)
				.collect(Collectors.groupingBy(Patient::getReferralPartnerId, Collectors.counting()));
		return partnerRepository.findByOrganizationIdOrderByNameAsc(orgId).stream()
				.map(p -> toPartner(p, byPartner.getOrDefault(p.getId(), List.of()), patientsByPartner.getOrDefault(p.getId(), 0L)))
				.toList();
	}

	@Transactional
	public PartnerResponse createPartner(UserPrincipal principal, PartnerRequest request) {
		support.requireAccess(principal);
		ReferralPartner partner = new ReferralPartner();
		applyPartner(partner, request);
		if (partner.getOwnerUserId() == null) {
			partner.setOwnerUserId(principal.userId());
			partner.setOwnerName(support.actorName(principal));
		}
		return toPartner(partnerRepository.save(partner), List.of(), 0);
	}

	@Transactional
	public PartnerResponse updatePartner(UserPrincipal principal, UUID id, PartnerRequest request) {
		support.requireAccess(principal);
		ReferralPartner partner = support.requirePartner(id);
		applyPartner(partner, request);
		ReferralPartner saved = partnerRepository.save(partner);
		List<Referral> referrals = repository.findByOrganizationIdOrderByReceivedOnDescCreatedAtDesc(support.orgId())
				.stream().filter(r -> id.equals(r.getReferralPartnerId())).toList();
		long patients = patientRepository.findByOrganizationIdOrderByCreatedAtDesc(support.orgId()).stream()
				.filter(p -> id.equals(p.getReferralPartnerId())).count();
		return toPartner(saved, referrals, patients);
	}

	@Transactional
	public void deletePartner(UserPrincipal principal, UUID id) {
		support.requireAdmin(principal);
		partnerRepository.delete(support.requirePartner(id));
	}

	// ── referrals ───────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<ReferralResponse> list(
			UserPrincipal principal,
			ReferralStatus status,
			UUID partnerId,
			UUID patientId,
			boolean openOnly,
			String q
	) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		List<Referral> rows = patientId != null
				? repository.findByOrganizationIdAndPatientIdOrderByReceivedOnDesc(orgId, patientId)
				: repository.findByOrganizationIdOrderByReceivedOnDescCreatedAtDesc(orgId);
		Map<UUID, Patient> patients = support.patients(rows.stream().map(Referral::getPatientId).toList());
		Map<UUID, ReferralPartner> partners = support.partners();
		String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase();
		return rows.stream()
				.filter(r -> status == null || r.getStatus() == status)
				.filter(r -> partnerId == null || partnerId.equals(r.getReferralPartnerId()))
				.filter(r -> !openOnly || r.isOpen())
				.filter(r -> needle == null || matches(r, patients.get(r.getPatientId()), partners.get(r.getReferralPartnerId()), needle))
				.map(r -> CrmMapper.referral(r, patients.get(r.getPatientId()), partners.get(r.getReferralPartnerId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public ReferralResponse get(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		return toResponse(require(id));
	}

	@Transactional(readOnly = true)
	public List<ActivityResponse> timeline(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		Referral referral = require(id);
		Patient patient = patientRepository.findById(referral.getPatientId()).orElse(null);
		return activityRepository.findByOrganizationIdAndReferralIdOrderByOccurredAtDesc(support.orgId(), id).stream()
				.map(a -> CrmMapper.activity(a, patient))
				.toList();
	}

	@Transactional
	public ReferralResponse create(UserPrincipal principal, ReferralRequest request) {
		support.requireAccess(principal);
		ReferralPartner partner = request.referralPartnerId() == null ? null : support.requirePartner(request.referralPartnerId());
		Patient patient = resolvePatient(principal, request, partner);

		Referral referral = new Referral();
		referral.setPatientId(patient.getId());
		apply(referral, request);
		if (referral.getDueOn() == null) {
			referral.setDueOn(referral.getReceivedOn().plusDays(referral.getPriority() == Priority.URGENT ? 1 : 3));
		}
		if (referral.getAssignedUserId() == null) {
			UUID assignee = patient.getOwnerUserId() != null ? patient.getOwnerUserId() : principal.userId();
			referral.setAssignedUserId(assignee);
			referral.setAssignedName(support.memberName(assignee));
		}
		Referral saved = repository.save(referral);

		if (partner != null) {
			partner.setLastTouchAt(Instant.now());
			partnerRepository.save(partner);
		}
		String from = partner != null ? partner.getName()
				: saved.getReferringProviderName() != null ? saved.getReferringProviderName() : "an outside provider";
		support.log(principal, ActivityType.SYSTEM, patient.getId(), null, saved.getId(),
				"Referral received from " + from,
				saved.getServiceRequested() == null ? saved.getReason() : "Requested: " + saved.getServiceRequested());
		ZoneId zone = ZoneId.systemDefault();
		taskService.createSystemTask(principal, patient.getId(), null, saved.getId(),
				"Process referral: verify insurance & schedule " + patient.getFullName(), "INSURANCE_VERIFICATION",
				saved.getPriority(), saved.getDueOn().atTime(LocalTime.of(9, 0)).atZone(zone).toInstant(),
				saved.getAssignedUserId());
		return toResponse(saved);
	}

	@Transactional
	public ReferralResponse update(UserPrincipal principal, UUID id, ReferralRequest request) {
		support.requireAccess(principal);
		Referral referral = require(id);
		if (request.referralPartnerId() != null) {
			support.requirePartner(request.referralPartnerId());
		}
		if (request.patientId() != null && !request.patientId().equals(referral.getPatientId())) {
			support.requirePatient(request.patientId());
			referral.setPatientId(request.patientId());
		}
		ReferralStatus before = referral.getStatus();
		apply(referral, request);
		trackClose(referral, before);
		Referral saved = repository.save(referral);
		if (before != saved.getStatus()) {
			logStatus(principal, saved, before, null);
		}
		return toResponse(saved);
	}

	@Transactional
	public ReferralResponse changeStatus(UserPrincipal principal, UUID id, ReferralStatusRequest request) {
		support.requireAccess(principal);
		Referral referral = require(id);
		ReferralStatus before = referral.getStatus();
		if (before == request.status()) {
			return toResponse(referral);
		}
		referral.setStatus(request.status());
		if (!referral.isOpen()) {
			referral.setClosedReason(CrmSupport.clean(request.closedReason()));
		}
		trackClose(referral, before);
		Referral saved = repository.save(referral);
		logStatus(principal, saved, before, CrmSupport.clean(request.note()));
		return toResponse(saved);
	}

	/** Automation hook used by appointments: only moves an open referral forward. */
	public void advance(UserPrincipal principal, UUID referralId, ReferralStatus target) {
		if (referralId == null) {
			return;
		}
		repository.findByIdAndOrganizationId(referralId, support.orgId()).ifPresent(r -> {
			if (r.isOpen() && r.getStatus().ordinal() < target.ordinal()) {
				ReferralStatus before = r.getStatus();
				r.setStatus(target);
				trackClose(r, before);
				repository.save(r);
				logStatus(principal, r, before, "Updated automatically from the appointment.");
			}
		});
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		repository.delete(require(id));
	}

	// ── internals ───────────────────────────────────────────────────────

	private Patient resolvePatient(UserPrincipal principal, ReferralRequest request, ReferralPartner partner) {
		if (request.patientId() != null) {
			return support.requirePatient(request.patientId());
		}
		String first = CrmSupport.clean(request.patientFirstName());
		if (first == null || CrmSupport.normalizePhone(request.patientPhone()) == null) {
			throw ApiException.badRequest("Select an existing patient, or enter the referred patient's first name and phone.");
		}
		// Re-use an existing record when the phone number already belongs to someone.
		List<Patient> existing = patientRepository.findByPhone(support.orgId(), CrmSupport.normalizePhone(request.patientPhone()));
		String lastName = CrmSupport.clean(request.patientLastName());
		for (Patient p : existing) {
			if (p.getFirstName() != null && p.getFirstName().equalsIgnoreCase(first)
					&& Objects.equals(lower(p.getLastName()), lower(lastName))) {
				return p;
			}
		}
		PatientRequest lead = new PatientRequest(
				first, lastName, null, request.patientDateOfBirth(), null, request.patientPhone(), null,
				CrmSupport.clean(request.patientEmail()), "Mobile", null, null, true, false, false, false,
				null, null, null, null, "Referral",
				partner != null ? partner.getName() : CrmSupport.clean(request.referringProviderName()), null,
				partner == null ? null : partner.getId(), CrmSupport.clean(request.serviceRequested()),
				CrmSupport.clean(request.referredToProvider()), null, CrmSupport.clean(request.reason()),
				CrmSupport.clean(request.insurancePayer()), null, null, false, null, null,
				request.priority() == null ? Priority.MEDIUM : request.priority(),
				request.assignedUserId(), null, null, null, null, null, null, null, null, null);
		return patientService.createRecord(principal, lead, true, false);
	}

	private void apply(Referral r, ReferralRequest req) {
		r.setReferralPartnerId(req.referralPartnerId());
		r.setDirection(req.direction() == null || req.direction().isBlank() ? "INBOUND" : req.direction().trim().toUpperCase());
		if (req.status() != null) {
			r.setStatus(req.status());
		}
		r.setPriority(req.priority() == null ? Priority.MEDIUM : req.priority());
		r.setReferringProviderName(CrmSupport.clean(req.referringProviderName()));
		r.setReferringProviderNpi(CrmSupport.clean(req.referringProviderNpi()));
		r.setReferredToProvider(CrmSupport.clean(req.referredToProvider()));
		r.setServiceRequested(CrmSupport.clean(req.serviceRequested()));
		r.setReason(CrmSupport.clean(req.reason()));
		r.setDiagnosisCodes(CrmSupport.clean(req.diagnosisCodes()));
		r.setInsurancePayer(CrmSupport.clean(req.insurancePayer()));
		r.setAuthorizationNumber(CrmSupport.clean(req.authorizationNumber()));
		r.setAuthorizationExpiresOn(req.authorizationExpiresOn());
		r.setReceivedOn(req.receivedOn() == null ? (r.getReceivedOn() == null ? LocalDate.now() : r.getReceivedOn()) : req.receivedOn());
		r.setDueOn(req.dueOn());
		if (req.assignedUserId() != null || r.getId() != null) {
			r.setAssignedUserId(req.assignedUserId());
			r.setAssignedName(support.memberName(req.assignedUserId()));
		}
		r.setNotes(CrmSupport.clean(req.notes()));
	}

	private void applyPartner(ReferralPartner p, PartnerRequest r) {
		p.setName(r.name().trim());
		p.setPartnerType(r.partnerType() == null || r.partnerType().isBlank() ? "PHYSICIAN" : r.partnerType().trim());
		p.setRelationshipStatus(r.relationshipStatus() == null || r.relationshipStatus().isBlank() ? "ACTIVE" : r.relationshipStatus().trim());
		p.setNpi(CrmSupport.clean(r.npi()));
		p.setSpecialty(CrmSupport.clean(r.specialty()));
		p.setContactName(CrmSupport.clean(r.contactName()));
		p.setPhone(CrmSupport.clean(r.phone()));
		p.setFax(CrmSupport.clean(r.fax()));
		p.setEmail(CrmSupport.clean(r.email()));
		p.setStreet(CrmSupport.clean(r.street()));
		p.setCity(CrmSupport.clean(r.city()));
		p.setState(CrmSupport.clean(r.state()));
		p.setZip(CrmSupport.clean(r.zip()));
		if (r.ownerUserId() != null || p.getId() != null) {
			p.setOwnerUserId(r.ownerUserId());
			p.setOwnerName(support.memberName(r.ownerUserId()));
		}
		p.setNotes(CrmSupport.clean(r.notes()));
	}

	private void trackClose(Referral r, ReferralStatus before) {
		boolean wasOpen = before != ReferralStatus.COMPLETED && before != ReferralStatus.DECLINED
				&& before != ReferralStatus.CANCELLED;
		if (wasOpen && !r.isOpen()) {
			r.setClosedAt(Instant.now());
		} else if (r.isOpen()) {
			r.setClosedAt(null);
			r.setClosedReason(null);
		}
	}

	private void logStatus(UserPrincipal principal, Referral r, ReferralStatus before, String note) {
		String body = r.getClosedReason() != null && !r.isOpen() ? "Reason: " + r.getClosedReason() : null;
		if (note != null) {
			body = body == null ? note : body + "\n" + note;
		}
		support.log(principal, ActivityType.STATUS_CHANGE, r.getPatientId(), null, r.getId(),
				"Referral: " + CrmSupport.humanize(before) + " → " + CrmSupport.humanize(r.getStatus()), body);
	}

	private ReferralResponse toResponse(Referral r) {
		Patient patient = patientRepository.findById(r.getPatientId()).orElse(null);
		ReferralPartner partner = r.getReferralPartnerId() == null ? null
				: partnerRepository.findById(r.getReferralPartnerId()).orElse(null);
		return CrmMapper.referral(r, patient, partner);
	}

	private Referral require(UUID id) {
		return repository.findByIdAndOrganizationId(id, support.orgId())
				.orElseThrow(() -> ApiException.notFound("Referral not found."));
	}

	private PartnerResponse toPartner(ReferralPartner p, List<Referral> referrals, long patients) {
		LocalDate cutoff = LocalDate.now().minusDays(90);
		long open = referrals.stream().filter(Referral::isOpen).count();
		long recent = referrals.stream().filter(r -> !r.getReceivedOn().isBefore(cutoff)).count();
		return new PartnerResponse(
				p.getId(), p.getName(), p.getPartnerType(), p.getRelationshipStatus(), p.getNpi(), p.getSpecialty(),
				p.getContactName(), p.getPhone(), p.getFax(), p.getEmail(), p.getStreet(), p.getCity(), p.getState(),
				p.getZip(), p.getOwnerUserId(), p.getOwnerName(), p.getLastTouchAt(), p.getNotes(),
				referrals.size(), open, recent, patients, p.getCreatedAt(), p.getUpdatedAt()
		);
	}

	private static boolean matches(Referral r, Patient p, ReferralPartner partner, String needle) {
		return (p != null && p.getFullName().toLowerCase().contains(needle))
				|| (partner != null && partner.getName().toLowerCase().contains(needle))
				|| (r.getReferringProviderName() != null && r.getReferringProviderName().toLowerCase().contains(needle))
				|| (r.getServiceRequested() != null && r.getServiceRequested().toLowerCase().contains(needle));
	}

	private static String lower(String value) {
		return value == null ? null : value.toLowerCase();
	}
}
