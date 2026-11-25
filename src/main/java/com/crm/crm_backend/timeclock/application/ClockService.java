package com.crm.crm_backend.timeclock.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.EntryView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Member;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.NowRow;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.StatusView;
import com.crm.crm_backend.timeclock.domain.TcSettings;
import com.crm.crm_backend.timeclock.domain.TimeEntry;
import com.crm.crm_backend.timeclock.domain.Timesheet;
import com.crm.crm_backend.timeclock.infrastructure.TimeEntryRepository;

/** Punching: clock in, breaks, clock out, your own status and the live "who's working" board. */
@Service
public class ClockService {

	/** A shift still open after this long is flagged as a probable missed clock-out. */
	static final Duration LONG_SHIFT = Duration.ofHours(12);

	private final TimeEntryRepository entries;
	private final TcSupport support;
	private final EntryAuditor audit;

	public ClockService(TimeEntryRepository entries, TcSupport support, EntryAuditor audit) {
		this.entries = entries;
		this.support = support;
		this.audit = audit;
	}

	/* ── Punches ────────────────────────────────────────────────────── */

	@Transactional
	public StatusView clockIn(UserPrincipal p, String note) {
		support.require(p);
		UUID org = support.org(p);
		TcSettings s = support.settings(org);
		if (entries.findFirstByOrganizationIdAndUserIdAndClockOutIsNull(org, p.userId()).isPresent()) {
			throw ApiException.conflict("You're already clocked in.");
		}
		Instant now = now();
		LocalDate today = now.atZone(TcSupport.zone(s)).toLocalDate();
		String status = support.statusOn(org, p.userId(), today);
		if (!Timesheet.OPEN.equals(status) && !Timesheet.REJECTED.equals(status)) {
			throw ApiException.conflict("This pay period's timesheet is already " + status.toLowerCase()
					+ ". Ask a manager to reopen it before clocking in.");
		}
		TimeEntry e = new TimeEntry();
		e.setOrganizationId(org);
		e.setUserId(p.userId());
		e.setClockIn(now);
		e.setNote(TcSupport.clean(note, 500));
		e.setSource(TimeEntry.WEB);
		e.setCreatedBy(p.userId());
		e.setUpdatedBy(p.userId());
		try {
			entries.saveAndFlush(e);
		} catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("You're already clocked in.");
		}
		audit.record(p, e, "CREATED", "Clocked in", null);
		return status(p);
	}

	@Transactional
	public StatusView startBreak(UserPrincipal p) {
		support.require(p);
		TimeEntry e = open(p);
		if (e.getBreakStartedAt() != null) {
			throw ApiException.conflict("You're already on a break.");
		}
		e.setBreakStartedAt(now());
		e.setUpdatedBy(p.userId());
		entries.save(e);
		return status(p);
	}

	@Transactional
	public StatusView endBreak(UserPrincipal p) {
		support.require(p);
		TimeEntry e = open(p);
		if (e.getBreakStartedAt() == null) {
			throw ApiException.conflict("You're not on a break.");
		}
		finishBreak(e, now());
		e.setUpdatedBy(p.userId());
		entries.save(e);
		return status(p);
	}

	@Transactional
	public StatusView clockOut(UserPrincipal p, String note) {
		support.require(p);
		TimeEntry e = open(p);
		Instant now = now();
		if (!now.isAfter(e.getClockIn())) {
			now = e.getClockIn().plusSeconds(1);
		}
		if (e.getBreakStartedAt() != null) {
			finishBreak(e, now);
		}
		e.setClockOut(now);
		String extra = TcSupport.clean(note, 500);
		if (extra != null) {
			e.setNote(e.getNote() == null ? extra : TcSupport.clean(e.getNote() + " · " + extra, 1000));
		}
		e.setUpdatedBy(p.userId());
		entries.save(e);
		audit.record(p, e, "UPDATED", "Clocked out", null);
		return status(p);
	}

	private TimeEntry open(UserPrincipal p) {
		return entries.findFirstByOrganizationIdAndUserIdAndClockOutIsNull(support.org(p), p.userId())
				.orElseThrow(() -> ApiException.conflict("You're not clocked in."));
	}

	private static void finishBreak(TimeEntry e, Instant at) {
		long mins = Math.max(0, Duration.between(e.getBreakStartedAt(), at).toMinutes());
		e.setBreakMinutes((int) (e.getBreakMinutes() + mins));
		e.setBreakStartedAt(null);
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	/* ── My status ──────────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public StatusView status(UserPrincipal p) {
		support.require(p);
		UUID org = support.org(p);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		TimeCalc.Rules rules = TcSupport.rules(s);
		Instant now = Instant.now();
		LocalDate today = now.atZone(zone).toLocalDate();
		TimeCalc.Period period = TcSupport.period(s, today);
		LocalDate weekStart = TimeCalc.startOfWeek(today, rules.weekStart());

		LocalDate from = period.start().isBefore(weekStart) ? period.start() : weekStart;
		LocalDate weekFrom = TimeCalc.startOfWeek(from, rules.weekStart());
		LocalDate to = period.end().isAfter(weekStart.plusDays(6)) ? period.end() : weekStart.plusDays(6);
		List<TimeEntry> mine = entries.findByOrganizationIdAndUserIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAsc(
				org, p.userId(), TcSupport.startOf(weekFrom, zone), TcSupport.startOf(TimeCalc.startOfWeek(to, rules.weekStart()).plusDays(7), zone));
		TimeEntry open = entries.findFirstByOrganizationIdAndUserIdAndClockOutIsNull(org, p.userId()).orElse(null);
		if (open != null && mine.stream().noneMatch(e -> e.getId().equals(open.getId()))) {
			mine = new ArrayList<>(mine);
			mine.add(open);
		}

		Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(mine, rules, from, to, now);
		long todayMin = days.getOrDefault(today, empty(today)).workedMinutes();
		long weekMin = 0;
		long weekOt = 0;
		for (int i = 0; i < 7; i++) {
			TimeCalc.DayTotals d = days.getOrDefault(weekStart.plusDays(i), empty(today));
			weekMin += d.workedMinutes();
			weekOt += d.overtimeMinutes();
		}
		long periodMin = days.values().stream()
				.filter(d -> !d.date().isBefore(period.start()) && !d.date().isAfter(period.end()))
				.mapToLong(TimeCalc.DayTotals::workedMinutes).sum();

		String name = support.name(p.userId());
		String tsStatus = support.statusOn(org, p.userId(), today);
		List<EntryView> todays = mine.stream()
				.filter(e -> TimeCalc.workDate(e, zone).equals(today))
				.map(e -> TcSupport.view(e, name, s, now, false))
				.toList();

		String state = open == null ? "OUT" : open.isOnBreak() ? "BREAK" : "IN";
		Instant since = open == null ? null : open.isOnBreak() ? open.getBreakStartedAt() : open.getClockIn();
		return new StatusView(state, since, open == null ? null : TcSupport.view(open, name, s, now, false),
				todayMin, weekMin, weekOt, period.start(), period.end(), periodMin, tsStatus, todays,
				zone.getId(), s.isAllowManualEntries());
	}

	private static TimeCalc.DayTotals empty(LocalDate d) {
		return new TimeCalc.DayTotals(d, 0, 0, 0, 0);
	}

	/* ── Who's working ──────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public List<NowRow> team(UserPrincipal p) {
		support.requireAdmin(p);
		UUID org = support.org(p);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		TimeCalc.Rules rules = TcSupport.rules(s);
		Instant now = Instant.now();
		LocalDate today = now.atZone(zone).toLocalDate();
		LocalDate weekStart = TimeCalc.startOfWeek(today, rules.weekStart());

		Map<UUID, List<TimeEntry>> byUser = entries
				.findByOrganizationIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAsc(
						org, TcSupport.startOf(weekStart, zone), TcSupport.startOf(weekStart.plusDays(7), zone))
				.stream().collect(Collectors.groupingBy(TimeEntry::getUserId));
		Map<UUID, TimeEntry> openByUser = entries.findByOrganizationIdAndClockOutIsNull(org).stream()
				.collect(Collectors.toMap(TimeEntry::getUserId, e -> e, (a, b) -> a));
		Map<UUID, Map<LocalDate, TcSupport.DayOff>> off = support.timeOffAll(org, today, today);

		List<NowRow> rows = new ArrayList<>();
		for (Member m : support.members(org).values()) {
			List<TimeEntry> list = new ArrayList<>(byUser.getOrDefault(m.userId(), List.of()));
			TimeEntry open = openByUser.get(m.userId());
			if (open != null && list.stream().noneMatch(e -> e.getId().equals(open.getId()))) {
				list.add(open);
			}
			Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(list, rules, weekStart, weekStart.plusDays(6), now);
			long todayMin = days.getOrDefault(today, empty(today)).workedMinutes();
			long weekMin = days.values().stream().mapToLong(TimeCalc.DayTotals::workedMinutes).sum();
			String state = open == null ? "OUT" : open.isOnBreak() ? "BREAK" : "IN";
			Instant since = open == null ? lastOut(list) : open.isOnBreak() ? open.getBreakStartedAt() : open.getClockIn();
			boolean longShift = open != null && Duration.between(open.getClockIn(), now).compareTo(LONG_SHIFT) > 0;
			TcSupport.DayOff dayOff = off.getOrDefault(m.userId(), Map.of()).get(today);
			rows.add(new NowRow(m.userId(), m.name(), m.role(), state, since, todayMin, weekMin, longShift,
					dayOff == null ? null : String.join(", ", dayOff.labels())));
		}
		return rows;
	}

	private static Instant lastOut(List<TimeEntry> list) {
		return list.stream().map(TimeEntry::getClockOut).filter(o -> o != null).max(Instant::compareTo).orElse(null);
	}
}
