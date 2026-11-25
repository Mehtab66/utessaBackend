package com.crm.crm_backend.timeclock.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.EntryView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Member;
import com.crm.crm_backend.timeclock.domain.PtoRequest;
import com.crm.crm_backend.timeclock.domain.TcSettings;
import com.crm.crm_backend.timeclock.domain.TimeEntry;
import com.crm.crm_backend.timeclock.domain.Timesheet;
import com.crm.crm_backend.timeclock.infrastructure.PtoRequestRepository;
import com.crm.crm_backend.timeclock.infrastructure.TcSettingsRepository;
import com.crm.crm_backend.timeclock.infrastructure.TimesheetRepository;

/** Shared Time Clock helpers: permissions, the practice's rules, members, locks and time off by day. */
@Component
public class TcSupport {

	/** Every practice member: punch, own timesheet, own time off. */
	public static final String USE = "timeclock";
	/** Owner, admin, office manager and HR: team time, approvals, edits for others, payroll. */
	public static final String ADMIN = "tcadmin";
	/** Owner and admin: change pay period and overtime rules. */
	public static final String SETTINGS = "settings";

	/** Time off types that are paid (UNPAID is tracked but not paid). */
	static final List<String> PTO_TYPES = List.of("VACATION", "SICK", "PERSONAL", "BEREAVEMENT", "HOLIDAY", "UNPAID");

	private final TcSettingsRepository settingsRepo;
	private final MembershipRepository memberships;
	private final UserRepository users;
	private final TimesheetRepository timesheets;
	private final PtoRequestRepository pto;

	public TcSupport(TcSettingsRepository settingsRepo, MembershipRepository memberships, UserRepository users,
			TimesheetRepository timesheets, PtoRequestRepository pto) {
		this.settingsRepo = settingsRepo;
		this.memberships = memberships;
		this.users = users;
		this.timesheets = timesheets;
		this.pto = pto;
	}

	/* ── Access ─────────────────────────────────────────────────────── */

	public void require(UserPrincipal p) {
		RoleGuard.requireOrgAccess(p, USE);
	}

	public void requireAdmin(UserPrincipal p) {
		RoleGuard.requireOrgAccess(p, ADMIN);
	}

	public boolean isAdmin(UserPrincipal p) {
		return has(p, ADMIN);
	}

	public boolean canEditSettings(UserPrincipal p) {
		return has(p, SETTINGS);
	}

	private static boolean has(UserPrincipal p, String perm) {
		try {
			RoleGuard.requireOrgAccess(p, perm);
			return true;
		} catch (ApiException e) {
			return false;
		}
	}

	/** Managers approve other people's time; only the owner may approve their own. */
	public void requireMayDecideFor(UserPrincipal p, UUID subject) {
		requireAdmin(p);
		if (p.userId().equals(subject) && !"OWNER".equalsIgnoreCase(p.role())) {
			throw ApiException.forbidden("You can't approve your own time. Another manager has to.");
		}
	}

	public UUID org(UserPrincipal p) {
		return p.organizationId();
	}

	/* ── Rules ──────────────────────────────────────────────────────── */

	/** The practice's settings, or the defaults (not saved) until someone changes them. */
	public TcSettings settings(UUID org) {
		return settingsRepo.findById(org).orElseGet(() -> {
			TcSettings s = new TcSettings();
			s.setOrganizationId(org);
			ZoneId zone = ZoneId.of(s.getTimezone());
			s.setPeriodAnchor(TimeCalc.startOfWeek(LocalDate.now(zone), DayOfWeek.MONDAY));
			return s;
		});
	}

	public static ZoneId zone(TcSettings s) {
		try {
			return ZoneId.of(s.getTimezone());
		} catch (Exception e) {
			return ZoneId.of("America/New_York");
		}
	}

	public static TimeCalc.Rules rules(TcSettings s) {
		return new TimeCalc.Rules(zone(s), DayOfWeek.valueOf(s.getWeekStart()), minutes(s.getDailyOtHours()),
				minutes(s.getWeeklyOtHours()), s.getRoundingMinutes());
	}

	private static Integer minutes(BigDecimal hours) {
		return hours == null ? null : hours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).intValue();
	}

	public static TimeCalc.Period period(TcSettings s, LocalDate day) {
		return TimeCalc.periodFor(day, s.getPayPeriod(), s.getPeriodAnchor());
	}

	public static Instant startOf(LocalDate day, ZoneId zone) {
		return day.atStartOfDay(zone).toInstant();
	}

	/* ── People ─────────────────────────────────────────────────────── */

	/** Practice members by user id, sorted by name. */
	public Map<UUID, Member> members(UUID org) {
		List<Membership> list = memberships.findByOrganizationId(org);
		Map<UUID, User> byId = users.findAllById(list.stream().map(Membership::getUserId).toList()).stream()
				.collect(Collectors.toMap(User::getId, Function.identity()));
		return list.stream()
				.filter(m -> byId.containsKey(m.getUserId()))
				.map(m -> {
					User u = byId.get(m.getUserId());
					return new Member(u.getId(), u.getFullName(), u.getEmail(), m.getRole().name());
				})
				.sorted(Comparator.comparing(Member::name, String.CASE_INSENSITIVE_ORDER))
				.collect(Collectors.toMap(Member::userId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
	}

	public Member member(UUID org, UUID userId) {
		return memberships.findByUserIdAndOrganizationId(userId, org)
				.flatMap(m -> users.findById(userId).map(u -> new Member(u.getId(), u.getFullName(), u.getEmail(), m.getRole().name())))
				.orElseThrow(() -> ApiException.notFound("That person isn't a member of this practice."));
	}

	public String name(UUID userId) {
		return users.findById(userId).map(User::getFullName).orElse("Former member");
	}

	/* ── Locks ──────────────────────────────────────────────────────── */

	/** Approval status of the pay period that contains {@code day} for this person. */
	public String statusOn(UUID org, UUID userId, LocalDate day) {
		return timesheets.findByOrganizationIdAndUserIdAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(org, userId, day, day)
				.stream().map(Timesheet::getStatus).findFirst().orElse(Timesheet.OPEN);
	}

	/**
	 * Refuses a change to a day whose timesheet is approved (by anyone) or submitted (by the
	 * employee — managers may still correct a submitted timesheet before approving it).
	 */
	public void requireEditable(UUID org, UUID userId, LocalDate day, boolean admin) {
		String status = statusOn(org, userId, day);
		if (Timesheet.APPROVED.equals(status)) {
			throw ApiException.conflict("That pay period is already approved. A manager has to reopen it before the time can change.");
		}
		if (Timesheet.SUBMITTED.equals(status) && !admin) {
			throw ApiException.conflict("That timesheet is submitted for approval. Ask your manager to send it back if something needs fixing.");
		}
	}

	/* ── Time off by day ────────────────────────────────────────────── */

	public record DayOff(long minutes, List<String> labels) {
	}

	/** Approved time off per day for one person in [from, to]. Hours are spread evenly over the request's days. */
	public Map<LocalDate, DayOff> timeOff(UUID org, UUID userId, LocalDate from, LocalDate to) {
		return timeOff(pto.findByOrganizationIdAndUserIdOrderByStartDateDesc(org, userId), from, to);
	}

	/** Approved time off per person per day in [from, to]. */
	public Map<UUID, Map<LocalDate, DayOff>> timeOffAll(UUID org, LocalDate from, LocalDate to) {
		Map<UUID, List<PtoRequest>> byUser = pto.findByOrganizationIdOrderByStartDateDesc(org).stream()
				.collect(Collectors.groupingBy(PtoRequest::getUserId));
		Map<UUID, Map<LocalDate, DayOff>> out = new HashMap<>();
		byUser.forEach((user, list) -> out.put(user, timeOff(list, from, to)));
		return out;
	}

	private static Map<LocalDate, DayOff> timeOff(List<PtoRequest> requests, LocalDate from, LocalDate to) {
		Map<LocalDate, Long> minutes = new HashMap<>();
		Map<LocalDate, List<String>> labels = new HashMap<>();
		for (PtoRequest r : requests) {
			if (!PtoRequest.APPROVED.equals(r.getStatus()) || r.getEndDate().isBefore(from) || r.getStartDate().isAfter(to)) {
				continue;
			}
			long days = ChronoUnit.DAYS.between(r.getStartDate(), r.getEndDate()) + 1;
			long perDay = r.getHours().multiply(BigDecimal.valueOf(60)).divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP).longValue();
			boolean paid = !"UNPAID".equals(r.getPtoType());
			for (LocalDate d = r.getStartDate(); !d.isAfter(r.getEndDate()); d = d.plusDays(1)) {
				if (d.isBefore(from) || d.isAfter(to)) {
					continue;
				}
				if (paid) {
					minutes.merge(d, perDay, Long::sum);
				}
				labels.computeIfAbsent(d, k -> new ArrayList<>()).add(label(r.getPtoType()));
			}
		}
		Map<LocalDate, DayOff> out = new HashMap<>();
		labels.forEach((d, l) -> out.put(d, new DayOff(minutes.getOrDefault(d, 0L), l)));
		return out;
	}

	public static String label(String type) {
		return switch (type) {
			case "VACATION" -> "Vacation";
			case "SICK" -> "Sick";
			case "PERSONAL" -> "Personal";
			case "BEREAVEMENT" -> "Bereavement";
			case "HOLIDAY" -> "Holiday";
			case "UNPAID" -> "Unpaid leave";
			default -> type;
		};
	}

	/* ── Views ──────────────────────────────────────────────────────── */

	public static EntryView view(TimeEntry e, String userName, TcSettings s, Instant now, boolean locked) {
		ZoneId zone = zone(s);
		return new EntryView(e.getId(), e.getUserId(), userName, TimeCalc.workDate(e, zone), e.getClockIn(), e.getClockOut(),
				TimeCalc.breakMinutes(e, now), e.getBreakStartedAt(), TimeCalc.workedMinutes(e, now, s.getRoundingMinutes()),
				e.getNote(), e.getSource(), e.isEdited(), locked);
	}

	public static String clean(String v, int max) {
		if (v == null || v.isBlank()) {
			return null;
		}
		String t = v.trim();
		return t.length() > max ? t.substring(0, max) : t;
	}

	public static <T> Optional<T> first(List<T> list) {
		return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
	}
}
