package com.crm.crm_backend.timeclock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.crm.crm_backend.timeclock.application.TimeCalc;
import com.crm.crm_backend.timeclock.domain.TimeEntry;

/** Hours, rounding, pay periods and overtime — no Spring, no database. */
class TimeCalcTest {

	private static final ZoneId NY = ZoneId.of("America/New_York");
	private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

	private static TimeEntry shift(LocalDate day, String in, String out, int breakMinutes) {
		TimeEntry e = new TimeEntry();
		e.setClockIn(day.atTime(LocalTime.parse(in)).atZone(NY).toInstant());
		e.setClockOut(out == null ? null : day.atTime(LocalTime.parse(out)).atZone(NY).toInstant());
		e.setBreakMinutes(breakMinutes);
		return e;
	}

	private static TimeCalc.Rules rules(Integer dailyOtHours, Integer weeklyOtHours, int rounding) {
		return new TimeCalc.Rules(NY, DayOfWeek.MONDAY, dailyOtHours == null ? null : dailyOtHours * 60,
				weeklyOtHours == null ? null : weeklyOtHours * 60, rounding);
	}

	@Test
	void workedMinutesSubtractsBreaks() {
		TimeEntry e = shift(MONDAY, "09:00", "17:30", 30);
		assertThat(TimeCalc.workedMinutes(e, Instant.now(), 0)).isEqualTo(480);
	}

	@Test
	void openShiftCountsUpToNowIncludingRunningBreak() {
		TimeEntry e = shift(MONDAY, "09:00", null, 0);
		Instant now = MONDAY.atTime(12, 0).atZone(NY).toInstant();
		e.setBreakStartedAt(MONDAY.atTime(11, 45).atZone(NY).toInstant());
		assertThat(TimeCalc.workedMinutes(e, now, 15)).isEqualTo(165);
	}

	@Test
	void roundsToNearestIncrement() {
		Instant t = MONDAY.atTime(9, 7, 31).atZone(NY).toInstant();
		assertThat(TimeCalc.round(t, 15).atZone(NY).toLocalTime()).isEqualTo(LocalTime.of(9, 15));
		assertThat(TimeCalc.round(MONDAY.atTime(9, 7).atZone(NY).toInstant(), 15).atZone(NY).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(TimeCalc.round(t, 0)).isEqualTo(t); // no rounding: exact time
	}

	@Test
	void weeklyOvertimeGoesToTheLaterHoursOfTheWeek() {
		List<TimeEntry> list = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			list.add(shift(MONDAY.plusDays(i), "08:00", "17:00", 0)); // 9h a day = 45h
		}
		Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(list, rules(null, 40, 0), MONDAY, MONDAY.plusDays(6), Instant.now());
		assertThat(days.get(MONDAY).overtimeMinutes()).isZero();
		assertThat(days.get(MONDAY.plusDays(3)).overtimeMinutes()).isZero();          // 36h so far
		assertThat(days.get(MONDAY.plusDays(4)).regularMinutes()).isEqualTo(4 * 60);   // 40h reached
		assertThat(days.get(MONDAY.plusDays(4)).overtimeMinutes()).isEqualTo(5 * 60);
		long ot = days.values().stream().mapToLong(TimeCalc.DayTotals::overtimeMinutes).sum();
		assertThat(ot).isEqualTo(5 * 60);
	}

	@Test
	void dailyOvertimeCountsBeforeWeekly() {
		List<TimeEntry> list = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			list.add(shift(MONDAY.plusDays(i), "08:00", "18:00", 0)); // 10h a day
		}
		Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(list, rules(8, 40, 0), MONDAY, MONDAY.plusDays(6), Instant.now());
		long regular = days.values().stream().mapToLong(TimeCalc.DayTotals::regularMinutes).sum();
		long ot = days.values().stream().mapToLong(TimeCalc.DayTotals::overtimeMinutes).sum();
		assertThat(regular).isEqualTo(40 * 60);  // 8h × 5 — weekly limit not exceeded by regular hours
		assertThat(ot).isEqualTo(10 * 60);       // 2h × 5 daily overtime
	}

	@Test
	void rangeInsideAWeekStillUsesTheWholeWeekForOvertime() {
		List<TimeEntry> list = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			list.add(shift(MONDAY.plusDays(i), "08:00", "17:00", 0));
		}
		// Only Friday requested: its overtime depends on Mon–Thu.
		Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(list, rules(null, 40, 0), MONDAY.plusDays(4), MONDAY.plusDays(4), Instant.now());
		assertThat(days).hasSize(1);
		assertThat(days.get(MONDAY.plusDays(4)).overtimeMinutes()).isEqualTo(5 * 60);
	}

	@Test
	void payPeriods() {
		LocalDate anchor = MONDAY;
		assertThat(TimeCalc.periodFor(MONDAY.plusDays(13), "BIWEEKLY", anchor)).isEqualTo(new TimeCalc.Period(MONDAY, MONDAY.plusDays(13)));
		assertThat(TimeCalc.periodFor(MONDAY.plusDays(14), "BIWEEKLY", anchor).start()).isEqualTo(MONDAY.plusDays(14));
		assertThat(TimeCalc.periodFor(MONDAY.minusDays(1), "BIWEEKLY", anchor).start()).isEqualTo(MONDAY.minusDays(14));
		assertThat(TimeCalc.periodFor(MONDAY.plusDays(9), "WEEKLY", anchor).start()).isEqualTo(MONDAY.plusDays(7));
		assertThat(TimeCalc.periodFor(LocalDate.of(2026, 2, 20), "SEMI_MONTHLY", anchor))
				.isEqualTo(new TimeCalc.Period(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 28)));
		assertThat(TimeCalc.periodFor(LocalDate.of(2026, 2, 15), "SEMI_MONTHLY", anchor).start()).isEqualTo(LocalDate.of(2026, 2, 1));
	}

	@Test
	void shiftCountsOnTheDayItStarted() {
		TimeEntry overnight = shift(MONDAY, "22:00", null, 0);
		overnight.setClockOut(MONDAY.plusDays(1).atTime(6, 0).atZone(NY).toInstant());
		Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(List.of(overnight), rules(null, 40, 0), MONDAY, MONDAY.plusDays(1), Instant.now());
		assertThat(days.get(MONDAY).workedMinutes()).isEqualTo(8 * 60);
		assertThat(days.get(MONDAY.plusDays(1)).workedMinutes()).isZero();
	}
}
