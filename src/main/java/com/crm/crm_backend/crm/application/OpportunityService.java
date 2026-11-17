package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.OpportunityDtos.OpportunityRequest;
import com.crm.crm_backend.crm.api.dto.OpportunityDtos.OpportunityResponse;
import com.crm.crm_backend.crm.api.dto.OpportunityDtos.StageRequest;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Opportunity;
import com.crm.crm_backend.crm.domain.OpportunityStage;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.OpportunityRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** Service-line / treatment pipeline for patients and leads. */
@Service
public class OpportunityService {

	private final OpportunityRepository repository;
	private final ActivityRepository activityRepository;
	private final CrmSupport support;

	public OpportunityService(OpportunityRepository repository, ActivityRepository activityRepository, CrmSupport support) {
		this.repository = repository;
		this.activityRepository = activityRepository;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public List<OpportunityResponse> list(
			UserPrincipal principal,
			OpportunityStage stage,
			UUID ownerUserId,
			UUID patientId,
			String q
	) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		List<Opportunity> rows = patientId != null
				? repository.findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(orgId, patientId)
				: repository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
		Map<UUID, Patient> patients = support.patients(rows.stream().map(Opportunity::getPatientId).toList());
		String needle = q == null ? null : q.trim().toLowerCase();
		return rows.stream()
				.filter(o -> stage == null || o.getStage() == stage)
				.filter(o -> ownerUserId == null || ownerUserId.equals(o.getOwnerUserId()))
				.filter(o -> needle == null || needle.isEmpty() || matches(o, patients.get(o.getPatientId()), needle))
				.map(o -> CrmMapper.opportunity(o, patients.get(o.getPatientId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public OpportunityResponse get(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		Opportunity o = require(id);
		return CrmMapper.opportunity(o, support.requirePatient(o.getPatientId()));
	}

	@Transactional(readOnly = true)
	public List<ActivityResponse> timeline(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		Opportunity o = require(id);
		Patient patient = support.requirePatient(o.getPatientId());
		return activityRepository.findByOrganizationIdAndOpportunityIdOrderByOccurredAtDesc(support.orgId(), id)
				.stream()
				.map(a -> CrmMapper.activity(a, patient))
				.toList();
	}

	@Transactional
	public OpportunityResponse create(UserPrincipal principal, OpportunityRequest request) {
		support.requireAccess(principal);
		Patient patient = support.requirePatient(request.patientId());
		Opportunity o = new Opportunity();
		apply(o, request);
		if (o.getOwnerUserId() == null) {
			UUID owner = patient.getOwnerUserId() != null ? patient.getOwnerUserId() : principal.userId();
			o.setOwnerUserId(owner);
			o.setOwnerName(support.memberName(owner));
		}
		if (o.getProbability() == null) {
			o.setProbability(defaultProbability(o.getStage()));
		}
		if (!o.isOpen()) {
			o.setClosedAt(Instant.now());
		}
		Opportunity saved = repository.save(o);
		support.log(principal, ActivityType.SYSTEM, patient.getId(), saved.getId(), saved.getReferralId(),
				"Opportunity created: " + saved.getName(),
				saved.getServiceLine() == null ? null : "Service line: " + saved.getServiceLine());
		return CrmMapper.opportunity(saved, patient);
	}

	@Transactional
	public OpportunityResponse update(UserPrincipal principal, UUID id, OpportunityRequest request) {
		support.requireAccess(principal);
		Opportunity o = require(id);
		Patient patient = support.requirePatient(request.patientId());
		OpportunityStage before = o.getStage();
		apply(o, request);
		trackClose(o, before);
		Opportunity saved = repository.save(o);
		if (before != saved.getStage()) {
			logStage(principal, saved, before, null);
		}
		return CrmMapper.opportunity(saved, patient);
	}

	@Transactional
	public OpportunityResponse changeStage(UserPrincipal principal, UUID id, StageRequest request) {
		support.requireAccess(principal);
		Opportunity o = require(id);
		OpportunityStage before = o.getStage();
		if (before == request.stage()) {
			return CrmMapper.opportunity(o, support.requirePatient(o.getPatientId()));
		}
		if (request.stage() == OpportunityStage.LOST && CrmSupport.clean(request.lostReason()) == null) {
			throw ApiException.badRequest("Add a reason when marking an opportunity as lost.");
		}
		o.setStage(request.stage());
		o.setProbability(defaultProbability(request.stage()));
		if (request.stage() == OpportunityStage.LOST) {
			o.setLostReason(CrmSupport.clean(request.lostReason()));
		}
		trackClose(o, before);
		Opportunity saved = repository.save(o);
		logStage(principal, saved, before, CrmSupport.clean(request.note()));
		return CrmMapper.opportunity(saved, support.requirePatient(saved.getPatientId()));
	}

	/** Automation hook used by appointments: only ever moves a deal forward. */
	public void advance(UserPrincipal principal, UUID opportunityId, OpportunityStage target) {
		if (opportunityId == null) {
			return;
		}
		repository.findByIdAndOrganizationId(opportunityId, support.orgId()).ifPresent(o -> {
			if (o.isOpen() && o.getStage().ordinal() < target.ordinal()) {
				OpportunityStage before = o.getStage();
				o.setStage(target);
				o.setProbability(defaultProbability(target));
				repository.save(o);
				logStage(principal, o, before, "Updated automatically from the appointment.");
			}
		});
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		repository.delete(require(id));
	}

	private Opportunity require(UUID id) {
		return repository.findByIdAndOrganizationId(id, support.orgId())
				.orElseThrow(() -> ApiException.notFound("Opportunity not found."));
	}

	private void logStage(UserPrincipal principal, Opportunity o, OpportunityStage before, String note) {
		support.log(principal, ActivityType.STATUS_CHANGE, o.getPatientId(), o.getId(), o.getReferralId(),
				o.getName() + ": " + CrmSupport.humanize(before) + " → " + CrmSupport.humanize(o.getStage()),
				o.getStage() == OpportunityStage.LOST && o.getLostReason() != null
						? "Reason: " + o.getLostReason() + (note == null ? "" : "\n" + note)
						: note);
	}

	private void trackClose(Opportunity o, OpportunityStage before) {
		boolean wasOpen = before != OpportunityStage.WON && before != OpportunityStage.LOST;
		if (wasOpen && !o.isOpen()) {
			o.setClosedAt(Instant.now());
		} else if (o.isOpen()) {
			o.setClosedAt(null);
		}
	}

	private void apply(Opportunity o, OpportunityRequest r) {
		o.setPatientId(r.patientId());
		o.setReferralId(r.referralId());
		o.setName(r.name().trim());
		o.setServiceLine(CrmSupport.clean(r.serviceLine()));
		o.setStage(r.stage() == null ? OpportunityStage.INQUIRY : r.stage());
		o.setPriority(r.priority() == null ? Priority.MEDIUM : r.priority());
		o.setEstimatedValue(r.estimatedValue());
		o.setProbability(r.probability());
		o.setExpectedCloseDate(r.expectedCloseDate());
		o.setProviderName(CrmSupport.clean(r.providerName()));
		o.setOwnerUserId(r.ownerUserId());
		o.setOwnerName(support.memberName(r.ownerUserId()));
		o.setLostReason(CrmSupport.clean(r.lostReason()));
		o.setNotes(CrmSupport.clean(r.notes()));
	}

	private static boolean matches(Opportunity o, Patient p, String needle) {
		return o.getName().toLowerCase().contains(needle)
				|| (o.getServiceLine() != null && o.getServiceLine().toLowerCase().contains(needle))
				|| (p != null && p.getFullName().toLowerCase().contains(needle));
	}

	static int defaultProbability(OpportunityStage stage) {
		return switch (stage) {
			case INQUIRY -> 10;
			case QUALIFIED -> 25;
			case CONSULT_SCHEDULED -> 40;
			case CONSULT_COMPLETED -> 60;
			case TREATMENT_PRESENTED -> 75;
			case WON -> 100;
			case LOST -> 0;
		};
	}
}
