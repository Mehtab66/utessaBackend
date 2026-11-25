package com.crm.crm_backend.operations.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.api.dto.OpsDtos.ComplianceItem;
import com.crm.crm_backend.operations.api.dto.OpsDtos.ProviderCompliance;
import com.crm.crm_backend.operations.domain.EmploymentStatus;
import com.crm.crm_backend.operations.domain.Facility;
import com.crm.crm_backend.operations.domain.Payer;
import com.crm.crm_backend.operations.domain.PayerEnrollment;
import com.crm.crm_backend.operations.domain.Privilege;
import com.crm.crm_backend.operations.domain.StaffMember;
import com.crm.crm_backend.operations.domain.TrackerRow;
import com.crm.crm_backend.operations.domain.TrackerTab;
import com.crm.crm_backend.operations.infrastructure.FacilityRepository;
import com.crm.crm_backend.operations.infrastructure.PayerEnrollmentRepository;
import com.crm.crm_backend.operations.infrastructure.PayerRepository;
import com.crm.crm_backend.operations.infrastructure.PrivilegeRepository;
import com.crm.crm_backend.operations.infrastructure.StaffMemberRepository;
import com.crm.crm_backend.operations.infrastructure.TrackerRowRepository;
import com.crm.crm_backend.operations.infrastructure.TrackerTabRepository;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * One compliance picture per provider: credentials and their expirations, payer enrollments,
 * hospital privileges and tracker dates (malpractice, peer review, contracts, CME, onboarding).
 */
@Service
public class ComplianceService {

	static final int DUE_WINDOW_DAYS = 90;
	private static final Set<String> OPEN_ENROLLMENT = Set.of("Application submitted", "In process", "Additional info requested");
	private static final Set<String> OPEN_PRIVILEGE = Set.of("Application submitted", "Credentials committee review", "MEC / board approval pending");

	private final OpsSupport ops;
	private final OpsDefaultsService defaults;
	private final StaffMemberRepository staffRepository;
	private final PayerRepository payerRepository;
	private final PayerEnrollmentRepository enrollmentRepository;
	private final FacilityRepository facilityRepository;
	private final PrivilegeRepository privilegeRepository;
	private final TrackerTabRepository tabRepository;
	private final TrackerRowRepository rowRepository;

	public ComplianceService(
			OpsSupport ops,
			OpsDefaultsService defaults,
			StaffMemberRepository staffRepository,
			PayerRepository payerRepository,
			PayerEnrollmentRepository enrollmentRepository,
			FacilityRepository facilityRepository,
			PrivilegeRepository privilegeRepository,
			TrackerTabRepository tabRepository,
			TrackerRowRepository rowRepository
	) {
		this.ops = ops;
		this.defaults = defaults;
		this.staffRepository = staffRepository;
		this.payerRepository = payerRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.facilityRepository = facilityRepository;
		this.privilegeRepository = privilegeRepository;
		this.tabRepository = tabRepository;
		this.rowRepository = rowRepository;
	}

	@Transactional
	public List<ProviderCompliance> providers(UserPrincipal principal) {
		ops.require(principal, OpsSupport.CREDS);
		UUID org = ops.orgId();
		defaults.ensurePayers(org);
		defaults.ensureTrackers(org);
		LocalDate today = LocalDate.now();
		Map<UUID, Payer> payers = payerRepository.findByOrganizationIdOrderBySortOrderAscNameAsc(org).stream().collect(Collectors.toMap(Payer::getId, Function.identity()));
		Map<UUID, Facility> facilities = facilityRepository.findByOrganizationIdOrderByNameAsc(org).stream().collect(Collectors.toMap(Facility::getId, Function.identity()));
		Map<UUID, TrackerTab> tabs = tabRepository.findByOrganizationIdAndScopeOrderBySortOrderAscNameAsc(org, "PROVIDER").stream().collect(Collectors.toMap(TrackerTab::getId, Function.identity()));
		Map<UUID, List<PayerEnrollment>> enrollments = enrollmentRepository.findByOrganizationId(org).stream().collect(Collectors.groupingBy(PayerEnrollment::getStaffId));
		Map<UUID, List<Privilege>> privileges = privilegeRepository.findByOrganizationId(org).stream().collect(Collectors.groupingBy(Privilege::getStaffId));
		Map<UUID, List<TrackerRow>> rows = rowRepository.findByOrganizationId(org).stream().filter(r -> tabs.containsKey(r.getTabId())).collect(Collectors.groupingBy(TrackerRow::getStaffId));

		List<ProviderCompliance> out = new ArrayList<>();
		for (StaffMember m : staffRepository.findByOrganizationIdAndProviderOrderByLastNameAscFirstNameAsc(org, true)) {
			if (m.getStatus() == EmploymentStatus.INACTIVE) {
				continue;
			}
			out.add(evaluate(m, today, payers, facilities, tabs,
					enrollments.getOrDefault(m.getId(), List.of()),
					privileges.getOrDefault(m.getId(), List.of()),
					rows.getOrDefault(m.getId(), List.of())));
		}
		return out;
	}

	ProviderCompliance evaluate(StaffMember m, LocalDate today, Map<UUID, Payer> payers, Map<UUID, Facility> facilities,
			Map<UUID, TrackerTab> tabs, List<PayerEnrollment> enrollments, List<Privilege> privileges, List<TrackerRow> rows) {
		Tally t = new Tally(today);

		// Core credentials on the provider record
		if (m.getLicenseNumber() == null) {
			t.missing("License", "State license not on file");
		} else {
			t.expiry("License", "State license", m.getLicenseExpiresOn(), m.getLicenseNumber());
		}
		if (m.getNpi() == null) {
			t.missing("NPI", "Individual NPI not on file");
		} else {
			t.ok();
		}
		if (m.getCaqhId() == null) {
			t.missing("CAQH", "CAQH ProView ID not on file");
		} else {
			t.ok();
		}
		if (m.getDeaNumber() != null) {
			t.expiry("DEA", "DEA registration", m.getDeaExpiresOn(), null);
		}
		if (m.getBoardExpiresOn() != null) {
			t.expiry("Board", m.getBoardName() == null ? "Board certification" : m.getBoardName(), m.getBoardExpiresOn(), null);
		}
		for (Map<String, Object> c : m.getCredentialsJson() == null ? List.<Map<String, Object>>of() : m.getCredentialsJson()) {
			LocalDate exp = date(c.get("expiresOn"));
			if (exp != null) {
				Object label = c.get("label") != null ? c.get("label") : c.get("type");
				t.expiry("Credential", label == null ? "Credential" : String.valueOf(label), exp, c.get("number") == null ? null : String.valueOf(c.get("number")));
			}
		}

		// Payers
		int approved = 0;
		for (PayerEnrollment e : enrollments) {
			Payer p = payers.get(e.getPayerId());
			String payer = p == null ? "Payer" : p.getShortName();
			if ("Approved".equals(e.getStatus())) {
				approved++;
				if (e.getRecredDue() != null) {
					t.expiry("Payer", payer + " re-credentialing", e.getRecredDue(), null);
				}
			} else if ("Additional info requested".equals(e.getStatus())) {
				t.action("Payer", payer + ": more information requested", e.getFollowUpOn());
			} else if (OPEN_ENROLLMENT.contains(e.getStatus()) && e.getFollowUpOn() != null && e.getFollowUpOn().isBefore(today)) {
				t.action("Payer", payer + ": follow-up overdue", e.getFollowUpOn());
			}
		}
		int payerTotal = (int) enrollments.stream().filter(e -> !"Not participating (by choice)".equals(e.getStatus())).count();

		// Hospital privileges
		int granted = 0;
		for (Privilege p : privileges) {
			Facility f = facilities.get(p.getFacilityId());
			String fac = f == null ? "Facility" : f.getName();
			if ("Granted".equals(p.getStatus()) || "Temporary privileges".equals(p.getStatus())) {
				granted++;
				t.expiry("Privileges", fac + (p.getStatus().startsWith("Temporary") ? " temporary privileges end" : " reappointment"), p.getReappointDue(), null);
			} else if (OPEN_PRIVILEGE.contains(p.getStatus())) {
				t.pending();
			}
			if (p.getFppeDue() != null && "In progress".equals(p.getFppeStatus())) {
				t.expiry("Privileges", fac + " FPPE", p.getFppeDue(), null);
			}
		}

		// Trackers
		double credits = 0;
		double need = 0;
		boolean hasMal = false;
		for (TrackerTab tab : tabs.values()) {
			List<TrackerRow> mine = rows.stream().filter(r -> r.getTabId().equals(tab.getId())).toList();
			if ("mal".equals(tab.getCode()) && !tab.isHidden()) {
				hasMal = !mine.isEmpty();
			}
			Map<String, Object> goal = tab.getGoal();
			if (goal != null && goal.get("need") != null) {
				need = num(goal.get("need"));
				int years = (int) Math.max(1, num(goal.get("years")));
				String field = String.valueOf(goal.getOrDefault("field", "credits"));
				LocalDate from = today.minusYears(years);
				for (TrackerRow r : mine) {
					LocalDate done = date(r.getVals().get("done"));
					if (done == null || !done.isBefore(from)) {
						credits += num(r.getVals().get(field));
					}
				}
			}
			for (TrackerRow r : mine) {
				for (Map<String, Object> f : tab.getFields()) {
					if ("expiry".equals(f.get("type"))) {
						LocalDate d = date(r.getVals().get(String.valueOf(f.get("id"))));
						if (d != null) {
							t.expiry(tab.getName(), tab.getName() + " · " + f.get("label"), d, null);
						}
					}
				}
				if ("onb".equals(tab.getCode())) {
					LocalDate due = date(r.getVals().get("due"));
					Object status = r.getVals().get("status");
					if (due != null && due.isBefore(today) && !"Done".equals(status)) {
						t.action("Onboarding", "Onboarding: " + r.getVals().getOrDefault("task", "step") + " overdue", due);
					}
				}
			}
		}
		if (!hasMal) {
			t.missing("Malpractice", "Malpractice insurance not on file");
		}
		if (need > 0) {
			if (credits >= need) {
				t.ok();
			} else if (m.getLicenseExpiresOn() != null && ChronoUnit.DAYS.between(today, m.getLicenseExpiresOn()) <= 180) {
				t.action("CME", trim(need - credits) + " CME credits short", m.getLicenseExpiresOn());
			} else {
				t.checks++;
			}
		}

		int denom = t.checks + payerTotal + privileges.size();
		int okNum = t.okCount + approved + granted;
		int readiness = denom == 0 ? 0 : (int) Math.round(okNum * 100.0 / denom);
		List<ComplianceItem> items = t.items.stream()
				.sorted(Comparator.comparingInt((ComplianceItem i) -> rank(i.state())).thenComparing(i -> i.days() == null ? Long.MAX_VALUE : i.days()))
				.toList();
		String status = items.stream().anyMatch(i -> !"DUE".equals(i.state())) ? "Action needed"
				: !items.isEmpty() ? "Renewal coming up" : "Fully compliant";
		return new ProviderCompliance(m.getId(), OpsSupport.displayName(m), status, readiness, items, t.checks, t.okCount,
				approved, payerTotal, granted, privileges.size(), credits, need, m.getLicenseExpiresOn());
	}

	private static int rank(String state) {
		return switch (state) {
			case "EXPIRED" -> 0;
			case "ACTION" -> 1;
			case "MISSING" -> 2;
			default -> 3;
		};
	}

	private static String trim(double d) {
		return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
	}

	static LocalDate date(Object v) {
		if (v == null) {
			return null;
		}
		String s = String.valueOf(v).trim();
		if (s.length() < 10) {
			return null;
		}
		try {
			return LocalDate.parse(s.substring(0, 10));
		} catch (RuntimeException ex) {
			return null;
		}
	}

	static double num(Object v) {
		if (v == null) {
			return 0;
		}
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		try {
			return Double.parseDouble(String.valueOf(v).trim());
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	/** Collects open items and counts the checks that passed. */
	private static final class Tally {
		final LocalDate today;
		final List<ComplianceItem> items = new ArrayList<>();
		int checks;
		int okCount;

		Tally(LocalDate today) {
			this.today = today;
		}

		void ok() {
			checks++;
			okCount++;
		}

		void pending() {
			// an application in process is neither done nor overdue: it does not count either way
		}

		void missing(String kind, String label) {
			checks++;
			items.add(new ComplianceItem(kind, label, "MISSING", null, null, null));
		}

		void action(String kind, String label, LocalDate due) {
			checks++;
			Long days = due == null ? null : ChronoUnit.DAYS.between(today, due);
			items.add(new ComplianceItem(kind, label, "ACTION", due, days, null));
		}

		void expiry(String kind, String label, LocalDate exp, String detail) {
			checks++;
			if (exp == null) {
				okCount++;
				return;
			}
			long days = ChronoUnit.DAYS.between(today, exp);
			if (days < 0) {
				items.add(new ComplianceItem(kind, label, "EXPIRED", exp, days, detail));
			} else if (days <= DUE_WINDOW_DAYS) {
				items.add(new ComplianceItem(kind, label, "DUE", exp, days, detail));
				okCount++;
			} else {
				okCount++;
			}
		}
	}
}
