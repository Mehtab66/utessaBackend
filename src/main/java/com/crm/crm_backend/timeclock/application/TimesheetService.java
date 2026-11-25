package com.crm.crm_backend.timeclock.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.DayView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.EntryView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Member;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.TeamPeriodRow;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.TeamPeriodView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.TimesheetView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Totals;
import com.crm.crm_backend.timeclock.domain.TcSettings;
import com.crm.crm_backend.timeclock.domain.TimeEntry;
import com.crm.crm_backend.timeclock.domain.Timesheet;
import com.crm.crm_backend.timeclock.infrastructure.TimeEntryRepository;
import com.crm.crm_backend.timeclock.infrastructure.TimesheetRepository;

/** One person's pay period: day-by-day hours, overtime, time off, and the approval workflow. */
@Service
public class TimesheetService {

	private final TimeEntryRepository entries;
	private final TimesheetRepository timesheets;
	private final TcSupport support;

	public TimesheetService(TimeEntryRepository entries, TimesheetRepository timesheets, TcSupport support) {
		this.entries = entries;
		this.timesheets = timesheets;
		this.support = support;
	}

	/* ── Reading ────────────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public TimesheetView view(UserPrincipal p, UUID userId, LocalDate periodStart) {
		support.require(p);
		UUID org = support.org(p);
		UUID subject = userId == null ? p.userId() : userId;
		boolean self = subject.equals(p.userId());
		if (!self) {
			support.requireAdmin(p);
		}
		Member member = self ? null : support.member(org, subject);
		TcSettings s = support.settings(org);
		TimeCalc.Period period = TcSupport.period(s, periodStart == null ? LocalDate.now(TcSupport.zone(s)) : periodStart);
		return build(p, org, s, subject, member == null ? support.name(subject) : member.name(), period);
	}

	/* ── Workflow ───────────────────────────────────────────────────── */

	@Transactional
	public TimesheetView submit(UserPrincipal p, LocalDate periodStart, String note) {
		support.require(p);
		UUID org = support.org(p);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		TimeCalc.Period period = TcSupport.period(s, periodStart == null ? LocalDate.now(zone) : periodStart);
		if (period.start().isAfter(LocalDate.now(zone))) {
			throw ApiException.badRequest("That pay period hasn't started yet.");
		}
		Timesheet t = timesheets.findByOrganizationIdAndUserIdAndPeriodStart(org, p.userId(), period.start()).orElse(null);
		if (t != null && Timesheet.APPROVED.equals(t.getStatus())) {
			throw ApiException.conflict("This timesheet is already approved.");
		}
		if (hasOpenShift(org, p.userId(), period, zone)) {
			throw ApiException.conflict("Clock out before submitting. A shift in this period is still running.");
		}
		if (t == null) {
			t = fresh(org, p.userId(), period);
		}
		t.setStatus(Timesheet.SUBMITTED);
		t.setSubmittedAt(Instant.now());
		t.setNote(TcSupport.clean(note, 1000));
		t.setDecidedAt(null);
		t.setDecidedBy(null);
		t.setDecidedByName(null);
		timesheets.save(t);
		return build(p, org, s, p.userId(), support.name(p.userId()), period);
	}

	@Transactional
	public TimesheetView approve(UserPrincipal p, UUID userId, LocalDate periodStart, String note) {
		UUID org = support.org(p);
		if (userId == null || periodStart == null) {
			throw ApiException.badRequest("Choose whose timesheet and which pay period.");
		}
		support.requireMayDecideFor(p, userId);
		Member m = support.member(org, userId);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		TimeCalc.Period period = TcSupport.period(s, periodStart);
		if (hasOpenShift(org, userId, period, zone)) {
			throw ApiException.conflict(m.name() + " is still clocked in on a shift in this period. Close that shift first.");
		}
		Timesheet t = timesheets.findByOrganizationIdAndUserIdAndPeriodStart(org, userId, period.start())
				.orElseGet(() -> fresh(org, userId, period));
		t.setStatus(Timesheet.APPROVED);
		decide(p, t, note);
		timesheets.save(t);
		return build(p, org, s, userId, m.name(), period);
	}

	/** Sends a timesheet back to the employee (also reopens an approved one). A note is required. */
	@Transactional
	public TimesheetView reject(UserPrincipal p, UUID userId, LocalDate periodStart, String note) {
		UUID org = support.org(p);
		if (userId == null || periodStart == null) {
			throw ApiException.badRequest("Choose whose timesheet and which pay period.");
		}
		support.requireMayDecideFor(p, userId);
		Member m = support.member(org, userId);
		if (TcSupport.clean(note, 1000) == null) {
			throw ApiException.badRequest("Tell them what needs fixing.");
		}
		TcSettings s = support.settings(org);
		TimeCalc.Period period = TcSupport.period(s, periodStart);
		Timesheet t = timesheets.findByOrganizationIdAndUserIdAndPeriodStart(org, userId, period.start())
				.orElseGet(() -> fresh(org, userId, period));
		t.setStatus(Timesheet.REJECTED);
		decide(p, t, note);
		timesheets.save(t);
		return build(p, org, s, userId, m.name(), period);
	}

	@Transactional(readOnly = true)
	public TeamPeriodView team(UserPrincipal p, LocalDate periodStart) {
		support.requireAdmin(p);
		UUID org = support.org(p);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		TimeCalc.Rules rules = TcSupport.rules(s);
		TimeCalc.Period period = TcSupport.period(s, periodStart == null ? LocalDate.now(zone) : periodStart);
		Instant now = Instant.now();

		Map<UUID, List<TimeEntry>> byUser = entriesFor(org, null, period, rules, zone).stream()
				.collect(Collectors.groupingBy(TimeEntry::getUserId));
		Map<UUID, Timesheet> sheets = timesheets.findByOrganizationIdAndPeriodStart(org, period.start()).stream()
				.collect(Collectors.toMap(Timesheet::getUserId, t -> t, (a, b) -> a));
		Map<UUID, Map<LocalDate, TcSupport.DayOff>> off = support.timeOffAll(org, period.start(), period.end());

		List<TeamPeriodRow> rows = new ArrayList<>();
		for (Member m : support.members(org).values()) {
			List<TimeEntry> list = byUser.getOrDefault(m.userId(), List.of());
			Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(list, rules, period.start(), period.end(), now);
			Totals totals = totals(days, off.getOrDefault(m.userId(), Map.of()));
			int shifts = (int) list.stream().filter(e -> inPeriod(e, period, zone)).count();
			boolean open = list.stream().anyMatch(e -> e.isOpen() && inPeriod(e, period, zone));
			Timesheet t = sheets.get(m.userId());
			rows.add(new TeamPeriodRow(m.userId(), m.name(), m.role(), t == null ? Timesheet.OPEN : t.getStatus(), totals, open,
					shifts, t == null ? null : t.getSubmittedAt()));
		}
		return new TeamPeriodView(period.start(), period.end(), TcSupport.period(s, period.start().minusDays(1)).start(),
				period.end().plusDays(1), rows);
	}

	/* ── Building the view ──────────────────────────────────────────── */

	private TimesheetView build(UserPrincipal p, UUID org, TcSettings s, UUID subject, String name, TimeCalc.Period period) {
		ZoneId zone = TcSupport.zone(s);
		TimeCalc.Rules rules = TcSupport.rules(s);
		Instant now = Instant.now();
		List<TimeEntry> list = entriesFor(org, subject, period, rules, zone);
		Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(list, rules, period.start(), period.end(), now);
		Map<LocalDate, TcSupport.DayOff> off = support.timeOff(org, subject, period.start(), period.end());

		Timesheet t = timesheets.findByOrganizationIdAndUserIdAndPeriodStart(org, subject, period.start()).orElse(null);
		String status = t == null ? Timesheet.OPEN : t.getStatus();
		boolean self = subject.equals(p.userId());
		boolean admin = support.isAdmin(p);
		boolean canEdit = !Timesheet.APPROVED.equals(status)
				&& (admin || (self && s.isAllowManualEntries() && !Timesheet.SUBMITTED.equals(status)));
		boolean locked = !canEdit;

		Map<LocalDate, List<EntryView>> entryViews = list.stream()
				.filter(e -> inPeriod(e, period, zone))
				.map(e -> TcSupport.view(e, name, s, now, locked))
				.collect(Collectors.groupingBy(EntryView::workDate));
		List<DayView> dayViews = new ArrayList<>();
		for (LocalDate d = period.start(); !d.isAfter(period.end()); d = d.plusDays(1)) {
			TimeCalc.DayTotals dt = days.get(d);
			TcSupport.DayOff o = off.get(d);
			dayViews.add(new DayView(d, dt.workedMinutes(), dt.regularMinutes(), dt.overtimeMinutes(), dt.breakMinutes(),
					o == null ? 0 : o.minutes(), o == null ? List.of() : o.labels(), entryViews.getOrDefault(d, List.of())));
		}
		boolean open = list.stream().anyMatch(e -> e.isOpen() && inPeriod(e, period, zone));
		boolean started = !period.start().isAfter(LocalDate.now(zone));
		boolean canSubmit = self && started && !open
				&& (Timesheet.OPEN.equals(status) || Timesheet.REJECTED.equals(status));
		boolean canDecide = admin && (!self || "OWNER".equalsIgnoreCase(p.role()));
		return new TimesheetView(t == null ? null : t.getId(), subject, name, period.start(), period.end(), status,
				t == null ? null : t.getNote(), t == null ? null : t.getSubmittedAt(), t == null ? null : t.getDecidedAt(),
				t == null ? null : t.getDecidedByName(), dayViews, totals(days, off), open, canEdit, canSubmit, canDecide, self,
				TcSupport.period(s, period.start().minusDays(1)).start(), period.end().plusDays(1));
	}

	static Totals totals(Map<LocalDate, TimeCalc.DayTotals> days, Map<LocalDate, TcSupport.DayOff> off) {
		long worked = 0;
		long regular = 0;
		long ot = 0;
		long breaks = 0;
		for (TimeCalc.DayTotals d : days.values()) {
			worked += d.workedMinutes();
			regular += d.regularMinutes();
			ot += d.overtimeMinutes();
			breaks += d.breakMinutes();
		}
		long pto = off.entrySet().stream().filter(e -> days.containsKey(e.getKey())).mapToLong(e -> e.getValue().minutes()).sum();
		return new Totals(worked, regular, ot, breaks, pto, regular + ot + pto);
	}

	/** Shifts for the whole workweeks around the period (needed for weekly overtime), plus any open shift. */
	List<TimeEntry> entriesFor(UUID org, UUID user, TimeCalc.Period period, TimeCalc.Rules rules, ZoneId zone) {
		LocalDate from = TimeCalc.startOfWeek(period.start(), rules.weekStart());
		LocalDate to = TimeCalc.startOfWeek(period.end(), rules.weekStart()).plusDays(7);
		Instant a = TcSupport.startOf(from, zone);
		Instant b = TcSupport.startOf(to, zone);
		return user == null
				? entries.findByOrganizationIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAsc(org, a, b)
				: entries.findByOrganizationIdAndUserIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAsc(org, user, a, b);
	}

	private boolean hasOpenShift(UUID org, UUID user, TimeCalc.Period period, ZoneId zone) {
		return entries.findFirstByOrganizationIdAndUserIdAndClockOutIsNull(org, user)
				.map(e -> inPeriod(e, period, zone))
				.orElse(false);
	}

	static boolean inPeriod(TimeEntry e, TimeCalc.Period period, ZoneId zone) {
		LocalDate d = TimeCalc.workDate(e, zone);
		return !d.isBefore(period.start()) && !d.isAfter(period.end());
	}

	private Timesheet fresh(UUID org, UUID user, TimeCalc.Period period) {
		Timesheet t = new Timesheet();
		t.setOrganizationId(org);
		t.setUserId(user);
		t.setPeriodStart(period.start());
		t.setPeriodEnd(period.end());
		return t;
	}

	private void decide(UserPrincipal p, Timesheet t, String note) {
		t.setDecidedBy(p.userId());
		t.setDecidedByName(support.name(p.userId()));
		t.setDecidedAt(Instant.now());
		String n = TcSupport.clean(note, 1000);
		if (n != null) {
			t.setNote(n);
		}
	}
}
