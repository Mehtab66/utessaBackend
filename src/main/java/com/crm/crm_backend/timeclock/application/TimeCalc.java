package com.crm.crm_backend.timeclock.application;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crm.crm_backend.timeclock.domain.TimeEntry;

/**
 * Pure time arithmetic for the Time Clock: worked minutes, rounding, pay periods and overtime.
 *
 * <p>Rules: a shift counts on the day it started (in the practice's time zone). Rounding applies to
 * finished shifts only. Overtime: hours over the daily limit are overtime first; then, within each
 * workweek, regular hours past the weekly limit are overtime (the later hours of the week).
 */
public final class TimeCalc {

	private TimeCalc() {
	}

	public record Rules(ZoneId zone, DayOfWeek weekStart, Integer dailyOtMinutes, Integer weeklyOtMinutes, int roundingMinutes) {
	}

	public record DayTotals(LocalDate date, long workedMinutes, long regularMinutes, long overtimeMinutes, long breakMinutes) {
	}

	public record Period(LocalDate start, LocalDate end) {
	}

	/* ── Shifts ─────────────────────────────────────────────────────── */

	/** Rounds to the nearest {@code minutes}; 0 or 1 = no rounding (exact time). */
	public static Instant round(Instant t, int minutes) {
		if (minutes <= 1) {
			return t;
		}
		Instant m = t.truncatedTo(ChronoUnit.MINUTES);
		if (t.getEpochSecond() % 60 >= 30) {
			m = m.plus(1, ChronoUnit.MINUTES);
		}
		long epochMin = m.getEpochSecond() / 60;
		long rem = Math.floorMod(epochMin, minutes);
		long rounded = rem * 2 >= minutes ? epochMin - rem + minutes : epochMin - rem;
		return Instant.ofEpochSecond(rounded * 60);
	}

	/** Paid minutes of one shift: (out − in) − breaks. An open shift counts up to {@code now}, unrounded. */
	public static long workedMinutes(TimeEntry e, Instant now, int rounding) {
		Instant in;
		Instant out;
		long breaks;
		if (e.isOpen()) {
			in = e.getClockIn();
			out = now;
			breaks = e.breakMinutesAt(now);
		} else {
			in = round(e.getClockIn(), rounding);
			out = round(e.getClockOut(), rounding);
			breaks = e.getBreakMinutes();
		}
		long gross = Math.max(0, Duration.between(in, out).toMinutes());
		return Math.max(0, gross - breaks);
	}

	public static long breakMinutes(TimeEntry e, Instant now) {
		return e.isOpen() ? e.breakMinutesAt(now) : e.getBreakMinutes();
	}

	public static LocalDate workDate(TimeEntry e, ZoneId zone) {
		return e.getClockIn().atZone(zone).toLocalDate();
	}

	/* ── Weeks and pay periods ──────────────────────────────────────── */

	public static LocalDate startOfWeek(LocalDate d, DayOfWeek weekStart) {
		return d.with(TemporalAdjusters.previousOrSame(weekStart));
	}

	public static Period periodFor(LocalDate d, String payPeriod, LocalDate anchor) {
		switch (payPeriod == null ? "BIWEEKLY" : payPeriod) {
			case "SEMI_MONTHLY" -> {
				return d.getDayOfMonth() <= 15
						? new Period(d.withDayOfMonth(1), d.withDayOfMonth(15))
						: new Period(d.withDayOfMonth(16), d.withDayOfMonth(d.lengthOfMonth()));
			}
			case "WEEKLY" -> {
				return fixed(d, anchor, 7);
			}
			default -> {
				return fixed(d, anchor, 14);
			}
		}
	}

	private static Period fixed(LocalDate d, LocalDate anchor, int length) {
		long diff = ChronoUnit.DAYS.between(anchor, d);
		long index = Math.floorDiv(diff, length);
		LocalDate start = anchor.plusDays(index * length);
		return new Period(start, start.plusDays(length - 1));
	}

	/* ── Daily totals with overtime ─────────────────────────────────── */

	/**
	 * Totals for every day in [from, to] for ONE person. {@code entries} must include every shift of
	 * the workweeks that overlap the range, so weekly overtime is counted correctly.
	 */
	public static Map<LocalDate, DayTotals> days(List<TimeEntry> entries, Rules r, LocalDate from, LocalDate to, Instant now) {
		LocalDate weekFrom = startOfWeek(from, r.weekStart());
		LocalDate weekTo = startOfWeek(to, r.weekStart()).plusDays(6);

		Map<LocalDate, Long> worked = new HashMap<>();
		Map<LocalDate, Long> breaks = new HashMap<>();
		for (TimeEntry e : entries) {
			LocalDate day = workDate(e, r.zone());
			if (day.isBefore(weekFrom) || day.isAfter(weekTo)) {
				continue;
			}
			worked.merge(day, workedMinutes(e, now, r.roundingMinutes()), Long::sum);
			breaks.merge(day, breakMinutes(e, now), Long::sum);
		}

		Map<LocalDate, DayTotals> out = new LinkedHashMap<>();
		for (LocalDate week = weekFrom; !week.isAfter(weekTo); week = week.plusWeeks(1)) {
			long weekRegular = 0;
			for (int i = 0; i < 7; i++) {
				LocalDate day = week.plusDays(i);
				long w = worked.getOrDefault(day, 0L);
				long dailyOt = r.dailyOtMinutes() == null ? 0 : Math.max(0, w - r.dailyOtMinutes());
				long regular = w - dailyOt;
				long weeklyOt = 0;
				if (r.weeklyOtMinutes() != null) {
					long room = Math.max(0, r.weeklyOtMinutes() - weekRegular);
					if (regular > room) {
						weeklyOt = regular - room;
						regular = room;
					}
				}
				weekRegular += regular;
				if (!day.isBefore(from) && !day.isAfter(to)) {
					out.put(day, new DayTotals(day, w, regular, dailyOt + weeklyOt, breaks.getOrDefault(day, 0L)));
				}
			}
		}
		return out;
	}
}
