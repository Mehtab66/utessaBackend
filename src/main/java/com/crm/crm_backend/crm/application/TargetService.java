package com.crm.crm_backend.crm.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.TargetDtos.TargetRequest;
import com.crm.crm_backend.crm.api.dto.TargetDtos.TargetResponse;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.AppointmentStatus;
import com.crm.crm_backend.crm.domain.CrmTarget;
import com.crm.crm_backend.crm.domain.Opportunity;
import com.crm.crm_backend.crm.domain.OpportunityStage;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Referral;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.AppointmentRepository;
import com.crm.crm_backend.crm.infrastructure.CrmTargetRepository;
import com.crm.crm_backend.crm.infrastructure.OpportunityRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.ReferralRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** Targets & quotas with live progress computed from CRM records. */
@Service
public class TargetService {

	public static final Set<String> METRICS = Set.of(
			"NEW_LEADS", "CALLS_MADE", "APPOINTMENTS_BOOKED", "APPOINTMENTS_COMPLETED",
			"CONVERSIONS", "REFERRALS_RECEIVED", "REVENUE_WON");

	private final CrmTargetRepository repository;
	private final PatientRepository patientRepository;
	private final AppointmentRepository appointmentRepository;
	private final OpportunityRepository opportunityRepository;
	private final ReferralRepository referralRepository;
	private final ActivityRepository activityRepository;
	private final CrmSupport support;

	public TargetService(
			CrmTargetRepository repository,
			PatientRepository patientRepository,
			AppointmentRepository appointmentRepository,
			OpportunityRepository opportunityRepository,
			ReferralRepository referralRepository,
			ActivityRepository activityRepository,
			CrmSupport support
	) {
		this.repository = repository;
		this.patientRepository = patientRepository;
		this.appointmentRepository = appointmentRepository;
		this.opportunityRepository = opportunityRepository;
		this.referralRepository = referralRepository;
		this.activityRepository = activityRepository;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public List<TargetResponse> list(UserPrincipal principal) {
		support.requireAccess(principal);
		Data data = load();
		return repository.findByOrganizationIdOrderByPeriodStartDesc(support.orgId()).stream()
				.map(t -> toResponse(t, data))
				.toList();
	}

	@Transactional
	public TargetResponse create(UserPrincipal principal, TargetRequest request) {
		support.requireAdmin(principal);
		CrmTarget target = new CrmTarget();
		target.setCreatedByName(support.actorName(principal));
		apply(target, request);
		return toResponse(repository.save(target), load());
	}

	@Transactional
	public TargetResponse update(UserPrincipal principal, UUID id, TargetRequest request) {
		support.requireAdmin(principal);
		CrmTarget target = require(id);
		apply(target, request);
		return toResponse(repository.save(target), load());
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		support.requireAdmin(principal);
		repository.delete(require(id));
	}

	private void apply(CrmTarget t, TargetRequest r) {
		String metric = r.metric().trim().toUpperCase();
		if (!METRICS.contains(metric)) {
			throw ApiException.badRequest("Unknown target metric.");
		}
		if (r.periodEnd().isBefore(r.periodStart())) {
			throw ApiException.badRequest("Period end must be on or after the start.");
		}
		t.setMetric(metric);
		t.setUserId(r.userId());
		t.setAssigneeName(r.userId() == null ? "Whole practice" : support.memberName(r.userId()));
		t.setTargetValue(r.targetValue());
		t.setPeriodLabel(CrmSupport.clean(r.periodLabel()));
		t.setPeriodStart(r.periodStart());
		t.setPeriodEnd(r.periodEnd());
		t.setNotes(CrmSupport.clean(r.notes()));
	}

	private CrmTarget require(UUID id) {
		return repository.findByIdAndOrganizationId(id, support.orgId())
				.orElseThrow(() -> ApiException.notFound("Target not found."));
	}

	private Data load() {
		UUID orgId = support.orgId();
		return new Data(
				patientRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId),
				appointmentRepository.findByOrganizationIdOrderByStartsAtAsc(orgId),
				opportunityRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId),
				referralRepository.findByOrganizationIdOrderByReceivedOnDescCreatedAtDesc(orgId),
				activityRepository.findByOrganizationIdAndActivityTypeInOrderByOccurredAtDesc(
						orgId, List.of(ActivityType.CALL_OUTBOUND), PageRequest.of(0, 20000))
		);
	}

	private TargetResponse toResponse(CrmTarget t, Data d) {
		ZoneId zone = ZoneId.systemDefault();
		Instant from = t.getPeriodStart().atStartOfDay(zone).toInstant();
		Instant to = t.getPeriodEnd().plusDays(1).atStartOfDay(zone).toInstant();
		Predicate<Instant> inPeriod = i -> i != null && !i.isBefore(from) && i.isBefore(to);
		UUID user = t.getUserId();

		BigDecimal achieved = switch (t.getMetric()) {
			case "NEW_LEADS" -> count(d.patients().stream()
					.filter(p -> user == null || user.equals(p.getOwnerUserId()))
					.filter(p -> inPeriod.test(p.getCreatedAt())).count());
			case "CONVERSIONS" -> count(d.patients().stream()
					.filter(p -> user == null || user.equals(p.getOwnerUserId()))
					.filter(p -> inPeriod.test(p.getConvertedAt())).count());
			case "APPOINTMENTS_BOOKED" -> count(d.appointments().stream()
					.filter(a -> user == null || user.equals(a.getAssignedUserId()))
					.filter(a -> inPeriod.test(a.getCreatedAt())).count());
			case "APPOINTMENTS_COMPLETED" -> count(d.appointments().stream()
					.filter(a -> user == null || user.equals(a.getAssignedUserId()))
					.filter(a -> a.getStatus() == AppointmentStatus.COMPLETED && inPeriod.test(a.getStartsAt())).count());
			case "REFERRALS_RECEIVED" -> count(d.referrals().stream()
					.filter(r -> user == null || user.equals(r.getAssignedUserId()))
					.filter(r -> !r.getReceivedOn().isBefore(t.getPeriodStart()) && !r.getReceivedOn().isAfter(t.getPeriodEnd()))
					.count());
			case "CALLS_MADE" -> count(d.calls().stream()
					.filter(a -> user == null || user.equals(a.getActorUserId()))
					.filter(a -> inPeriod.test(a.getOccurredAt())).count());
			case "REVENUE_WON" -> d.opportunities().stream()
					.filter(o -> user == null || user.equals(o.getOwnerUserId()))
					.filter(o -> o.getStage() == OpportunityStage.WON && inPeriod.test(o.getClosedAt()))
					.map(o -> o.getEstimatedValue() == null ? BigDecimal.ZERO : o.getEstimatedValue())
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			default -> BigDecimal.ZERO;
		};

		int percent = t.getTargetValue().signum() == 0 ? 0
				: achieved.multiply(BigDecimal.valueOf(100)).divide(t.getTargetValue(), 0, RoundingMode.HALF_UP).intValue();
		return new TargetResponse(t.getId(), t.getUserId(), t.getAssigneeName(), t.getMetric(), t.getTargetValue(),
				achieved, percent, health(t, percent), t.getPeriodLabel(), t.getPeriodStart(), t.getPeriodEnd(),
				t.getNotes(), t.getCreatedByName());
	}

	private static String health(CrmTarget t, int percent) {
		LocalDate today = LocalDate.now();
		if (percent >= 100) {
			return "ACHIEVED";
		}
		if (today.isBefore(t.getPeriodStart())) {
			return "UPCOMING";
		}
		if (today.isAfter(t.getPeriodEnd())) {
			return "MISSED";
		}
		long total = ChronoUnit.DAYS.between(t.getPeriodStart(), t.getPeriodEnd()) + 1;
		long elapsed = ChronoUnit.DAYS.between(t.getPeriodStart(), today) + 1;
		double expected = (double) elapsed / total * 100;
		if (percent >= expected - 10) {
			return "ON_TRACK";
		}
		return percent >= expected - 30 ? "AT_RISK" : "BEHIND";
	}

	private static BigDecimal count(long n) {
		return BigDecimal.valueOf(n);
	}

	private record Data(
			List<Patient> patients,
			List<Appointment> appointments,
			List<Opportunity> opportunities,
			List<Referral> referrals,
			List<Activity> calls
	) {
	}
}
