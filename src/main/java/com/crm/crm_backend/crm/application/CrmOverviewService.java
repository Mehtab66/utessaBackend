package com.crm.crm_backend.crm.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.CountItem;
import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.DashboardResponse;
import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.Kpis;
import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.PipelineItem;
import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.SearchResult;
import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.TeamMember;
import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.TrendPoint;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.AppointmentStatus;
import com.crm.crm_backend.crm.domain.CrmTask;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.Opportunity;
import com.crm.crm_backend.crm.domain.OpportunityStage;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.Referral;
import com.crm.crm_backend.crm.domain.ReferralPartner;
import com.crm.crm_backend.crm.domain.ReferralStatus;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.AppointmentRepository;
import com.crm.crm_backend.crm.infrastructure.CrmTaskRepository;
import com.crm.crm_backend.crm.infrastructure.OpportunityRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.ReferralRepository;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** CRM dashboard metrics, global search and the team directory used for assignment. */
@Service
public class CrmOverviewService {

	private static final List<LeadStatus> OPEN_LEAD = List.of(
			LeadStatus.NEW, LeadStatus.ATTEMPTING_CONTACT, LeadStatus.CONTACTED, LeadStatus.QUALIFIED,
			LeadStatus.APPOINTMENT_SCHEDULED, LeadStatus.SHOWED);

	private final PatientRepository patientRepository;
	private final AppointmentRepository appointmentRepository;
	private final CrmTaskRepository taskRepository;
	private final OpportunityRepository opportunityRepository;
	private final ReferralRepository referralRepository;
	private final ActivityRepository activityRepository;
	private final CrmSupport support;

	public CrmOverviewService(
			PatientRepository patientRepository,
			AppointmentRepository appointmentRepository,
			CrmTaskRepository taskRepository,
			OpportunityRepository opportunityRepository,
			ReferralRepository referralRepository,
			ActivityRepository activityRepository,
			CrmSupport support
	) {
		this.patientRepository = patientRepository;
		this.appointmentRepository = appointmentRepository;
		this.taskRepository = taskRepository;
		this.opportunityRepository = opportunityRepository;
		this.referralRepository = referralRepository;
		this.activityRepository = activityRepository;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public DashboardResponse dashboard(UserPrincipal principal, String tz) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		ZoneId zone = TaskService.zone(tz);
		LocalDate today = LocalDate.now(zone);
		Instant startToday = today.atStartOfDay(zone).toInstant();
		Instant startTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
		Instant startMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
		Instant now = Instant.now();

		List<Patient> patients = patientRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
		List<Appointment> appointments = appointmentRepository.findByOrganizationIdOrderByStartsAtAsc(orgId);
		List<CrmTask> tasks = taskRepository.findByOrganizationIdOrderByDueAtAsc(orgId);
		List<Opportunity> opps = opportunityRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
		List<Referral> referrals = referralRepository.findByOrganizationIdOrderByReceivedOnDescCreatedAtDesc(orgId);
		List<Activity> recent = activityRepository.findByOrganizationIdOrderByOccurredAtDesc(orgId, PageRequest.of(0, 500));
		Map<UUID, Patient> byId = patients.stream().collect(Collectors.toMap(Patient::getId, Function.identity()));

		List<Patient> leads = patients.stream().filter(Patient::isLead).toList();
		long newLeadsMonth = patients.stream()
				.filter(CrmOverviewService::startedAsLead)
				.filter(p -> !p.getCreatedAt().isBefore(startMonth)).count();
		long openLeads = leads.stream().filter(p -> OPEN_LEAD.contains(p.getLeadStatus())).count();
		long activePatients = patients.stream().filter(p -> p.getLifecycleStage() == Lifecycle.PATIENT).count();
		long converted = patients.stream().filter(p -> p.getConvertedAt() != null && startedAsLead(p)).count();
		long closedLeads = leads.stream().filter(p -> p.getLeadStatus() == LeadStatus.LOST || p.getLeadStatus() == LeadStatus.NOT_QUALIFIED).count();
		double conversionRate = converted + closedLeads + openLeads == 0 ? 0
				: round1((double) converted * 100 / (converted + closedLeads + openLeads));

		long apptsToday = appointments.stream()
				.filter(a -> a.getStatus() != AppointmentStatus.CANCELLED && a.getStatus() != AppointmentStatus.RESCHEDULED)
				.filter(a -> !a.getStartsAt().isBefore(startToday) && a.getStartsAt().isBefore(startTomorrow)).count();
		long apptsUpcoming = appointments.stream().filter(a -> a.isActive() && !a.getStartsAt().isBefore(now)).count();
		long tasksToday = tasks.stream().filter(CrmTask::isOpen)
				.filter(t -> !t.getDueAt().isBefore(startToday) && t.getDueAt().isBefore(startTomorrow)).count();
		long tasksOverdue = tasks.stream().filter(CrmTask::isOpen).filter(t -> t.getDueAt().isBefore(now)).count();
		long openReferrals = referrals.stream().filter(Referral::isOpen).count();
		long callsToday = recent.stream()
				.filter(a -> a.getActivityType() == ActivityType.CALL_INBOUND || a.getActivityType() == ActivityType.CALL_OUTBOUND)
				.filter(a -> !a.getOccurredAt().isBefore(startToday)).count();
		Instant ninetyDays = now.minusSeconds(90L * 86400);
		List<Appointment> pastVisits = appointments.stream()
				.filter(a -> a.getStartsAt().isAfter(ninetyDays) && a.getStartsAt().isBefore(now))
				.filter(a -> a.getStatus() == AppointmentStatus.COMPLETED || a.getStatus() == AppointmentStatus.NO_SHOW
						|| a.getStatus() == AppointmentStatus.CHECKED_IN)
				.toList();
		double noShowRate = pastVisits.isEmpty() ? 0
				: round1(pastVisits.stream().filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).count() * 100.0 / pastVisits.size());
		BigDecimal openPipeline = opps.stream().filter(Opportunity::isOpen).map(CrmOverviewService::value)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal wonMonth = opps.stream()
				.filter(o -> o.getStage() == OpportunityStage.WON && o.getClosedAt() != null && !o.getClosedAt().isBefore(startMonth))
				.map(CrmOverviewService::value).reduce(BigDecimal.ZERO, BigDecimal::add);

		Kpis kpis = new Kpis(newLeadsMonth, openLeads, activePatients, conversionRate, apptsToday, apptsUpcoming,
				tasksToday, tasksOverdue, openReferrals, callsToday, noShowRate, openPipeline, wonMonth);

		List<CountItem> funnel = Arrays.stream(LeadStatus.values())
				.map(s -> new CountItem(s.name(), CrmSupport.humanize(s),
						patients.stream().filter(p -> p.getLeadStatus() == s).count()))
				.toList();
		List<CountItem> sources = patients.stream()
				.collect(Collectors.groupingBy(p -> p.getSource() == null ? "Unknown" : p.getSource(), Collectors.counting()))
				.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(8)
				.map(e -> new CountItem(e.getKey(), e.getKey(), e.getValue()))
				.toList();
		List<CountItem> priorities = Arrays.stream(Priority.values())
				.map(p -> new CountItem(p.name(), CrmSupport.humanize(p),
						leads.stream().filter(l -> OPEN_LEAD.contains(l.getLeadStatus()) && l.getPriority() == p).count()))
				.toList();
		List<PipelineItem> pipeline = Arrays.stream(OpportunityStage.values())
				.map(s -> new PipelineItem(s.name(),
						opps.stream().filter(o -> o.getStage() == s).count(),
						opps.stream().filter(o -> o.getStage() == s).map(CrmOverviewService::value).reduce(BigDecimal.ZERO, BigDecimal::add)))
				.toList();
		List<CountItem> referralStatus = Arrays.stream(ReferralStatus.values())
				.map(s -> new CountItem(s.name(), CrmSupport.humanize(s),
						referrals.stream().filter(r -> r.getStatus() == s).count()))
				.toList();

		List<TrendPoint> trend = new ArrayList<>();
		YearMonth current = YearMonth.from(today);
		DateTimeFormatter label = DateTimeFormatter.ofPattern("MMM");
		for (int i = 5; i >= 0; i--) {
			YearMonth ym = current.minusMonths(i);
			Instant from = ym.atDay(1).atStartOfDay(zone).toInstant();
			Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
			trend.add(new TrendPoint(
					label.format(ym),
					patients.stream().filter(p -> between(p.getCreatedAt(), from, to)).count(),
					patients.stream().filter(p -> between(p.getConvertedAt(), from, to)).count(),
					appointments.stream().filter(a -> between(a.getCreatedAt(), from, to)).count()));
		}

		var upcoming = appointments.stream()
				.filter(a -> a.isActive() && !a.getStartsAt().isBefore(startToday))
				.limit(6)
				.map(a -> CrmMapper.appointment(a, byId.get(a.getPatientId())))
				.toList();
		var dueTasks = tasks.stream()
				.filter(CrmTask::isOpen)
				.filter(t -> t.getDueAt().isBefore(startTomorrow))
				.limit(8)
				.map(t -> CrmMapper.task(t, byId.get(t.getPatientId())))
				.toList();
		Map<UUID, ReferralPartner> partners = support.partners();
		var hotLeads = leads.stream()
				.filter(p -> OPEN_LEAD.contains(p.getLeadStatus()))
				.filter(p -> p.getPriority() == Priority.URGENT || p.getPriority() == Priority.HIGH)
				.sorted(Comparator.comparing(Patient::getPriority).reversed().thenComparing(Patient::getCreatedAt, Comparator.reverseOrder()))
				.limit(6)
				.map(p -> CrmMapper.patient(p, partners.get(p.getReferralPartnerId())))
				.toList();
		var activity = recent.stream()
				.limit(10)
				.map(a -> CrmMapper.activity(a, byId.get(a.getPatientId())))
				.toList();

		return new DashboardResponse(kpis, funnel, sources, priorities, pipeline, referralStatus, trend,
				upcoming, dueTasks, hotLeads, activity);
	}

	@Transactional(readOnly = true)
	public List<SearchResult> search(UserPrincipal principal, String q) {
		support.requireAccess(principal);
		if (q == null || q.trim().length() < 2) {
			return List.of();
		}
		UUID orgId = support.orgId();
		String needle = q.trim().toLowerCase();
		String digits = CrmSupport.normalizePhone(q);
		boolean phoneSearch = digits != null && digits.length() >= 3;
		List<SearchResult> results = new ArrayList<>();

		patientRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
				.filter(p -> p.getFullName().toLowerCase().contains(needle)
						|| (p.getEmail() != null && p.getEmail().toLowerCase().contains(needle))
						|| (p.getMrn() != null && p.getMrn().toLowerCase().contains(needle))
						|| (phoneSearch && ((p.getPhoneNormalized() != null && p.getPhoneNormalized().contains(digits))
								|| (p.getAltPhoneNormalized() != null && p.getAltPhoneNormalized().contains(digits)))))
				.limit(8)
				.forEach(p -> results.add(new SearchResult(p.isLead() ? "LEAD" : "PATIENT", p.getId(), p.getFullName(),
						joinNonNull(" · ", p.getPhone(), CrmSupport.humanize(p.getLeadStatus()), p.getMrn() == null ? null : "MRN " + p.getMrn()),
						"/app/crm/patients/" + p.getId())));

		Map<UUID, Patient> names = patientRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
				.collect(Collectors.toMap(Patient::getId, Function.identity()));
		opportunityRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
				.filter(o -> o.getName().toLowerCase().contains(needle)
						|| (o.getServiceLine() != null && o.getServiceLine().toLowerCase().contains(needle)))
				.limit(5)
				.forEach(o -> results.add(new SearchResult("OPPORTUNITY", o.getId(), o.getName(),
						joinNonNull(" · ", names.containsKey(o.getPatientId()) ? names.get(o.getPatientId()).getFullName() : null,
								CrmSupport.humanize(o.getStage())),
						"/app/crm/opportunities/" + o.getId())));

		support.partners().values().stream()
				.filter(p -> p.getName().toLowerCase().contains(needle)
						|| (p.getNpi() != null && p.getNpi().contains(needle))
						|| (p.getContactName() != null && p.getContactName().toLowerCase().contains(needle)))
				.limit(5)
				.forEach(p -> results.add(new SearchResult("PARTNER", p.getId(), p.getName(),
						joinNonNull(" · ", p.getSpecialty(), p.getCity()), "/app/crm/referrals?tab=partners")));
		return results;
	}

	@Transactional(readOnly = true)
	public List<TeamMember> team(UserPrincipal principal) {
		support.requireAccess(principal);
		return support.team();
	}

	private static BigDecimal value(Opportunity o) {
		return o.getEstimatedValue() == null ? BigDecimal.ZERO : o.getEstimatedValue();
	}

	private static boolean between(Instant value, Instant from, Instant to) {
		return value != null && !value.isBefore(from) && value.isBefore(to);
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static String joinNonNull(String sep, String... parts) {
		return Arrays.stream(parts).filter(Objects::nonNull).filter(s -> !s.isBlank()).collect(Collectors.joining(sep));
	}

	/** Records added directly as patients (e.g. imported) are not counted as leads. */
	private static boolean startedAsLead(Patient p) {
		return p.getConvertedAt() == null || p.getConvertedAt().isAfter(p.getCreatedAt().plusSeconds(1));
	}
}
