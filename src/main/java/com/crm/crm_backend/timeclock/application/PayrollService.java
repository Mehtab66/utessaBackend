package com.crm.crm_backend.timeclock.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Member;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PayrollRow;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PayrollView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Totals;
import com.crm.crm_backend.timeclock.domain.TcSettings;
import com.crm.crm_backend.timeclock.domain.TimeEntry;
import com.crm.crm_backend.timeclock.domain.Timesheet;
import com.crm.crm_backend.timeclock.infrastructure.TimeEntryRepository;
import com.crm.crm_backend.timeclock.infrastructure.TimesheetRepository;

/** Hours per person for a date range (default: the current pay period), and the CSV for payroll. */
@Service
public class PayrollService {

	private static final int MAX_DAYS = 62;

	private final TimeEntryRepository entries;
	private final TimesheetRepository timesheets;
	private final TcSupport support;

	public PayrollService(TimeEntryRepository entries, TimesheetRepository timesheets, TcSupport support) {
		this.entries = entries;
		this.timesheets = timesheets;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public PayrollView report(UserPrincipal p, LocalDate fromParam, LocalDate toParam) {
		support.requireAdmin(p);
		UUID org = support.org(p);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		TimeCalc.Rules rules = TcSupport.rules(s);
		TimeCalc.Period current = TcSupport.period(s, LocalDate.now(zone));
		// No end date: the whole pay period containing "from" (or the current one).
		TimeCalc.Period snapped = fromParam == null ? current : TcSupport.period(s, fromParam);
		LocalDate from = toParam == null ? snapped.start() : (fromParam == null ? current.start() : fromParam);
		LocalDate to = toParam == null ? snapped.end() : toParam;
		if (to.isBefore(from)) {
			throw ApiException.badRequest("The end date can't be before the start date.");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
			throw ApiException.badRequest("Choose a range of " + MAX_DAYS + " days or less.");
		}
		TimeCalc.Period exact = TcSupport.period(s, from);
		boolean isPeriod = exact.start().equals(from) && exact.end().equals(to);
		Instant now = Instant.now();

		LocalDate weekFrom = TimeCalc.startOfWeek(from, rules.weekStart());
		LocalDate weekTo = TimeCalc.startOfWeek(to, rules.weekStart()).plusDays(7);
		Map<UUID, List<TimeEntry>> byUser = entries
				.findByOrganizationIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAsc(
						org, TcSupport.startOf(weekFrom, zone), TcSupport.startOf(weekTo, zone))
				.stream().collect(Collectors.groupingBy(TimeEntry::getUserId));
		Map<UUID, Map<LocalDate, TcSupport.DayOff>> off = support.timeOffAll(org, from, to);
		Map<UUID, Timesheet> sheets = isPeriod
				? timesheets.findByOrganizationIdAndPeriodStart(org, from).stream()
						.collect(Collectors.toMap(Timesheet::getUserId, t -> t, (a, b) -> a))
				: Map.of();

		List<PayrollRow> rows = new ArrayList<>();
		Totals sum = Totals.ZERO;
		for (Member m : support.members(org).values()) {
			List<TimeEntry> list = byUser.getOrDefault(m.userId(), List.of());
			Map<LocalDate, TimeCalc.DayTotals> days = TimeCalc.days(list, rules, from, to, now);
			Totals t = TimesheetService.totals(days, off.getOrDefault(m.userId(), Map.of()));
			List<TimeEntry> inRange = list.stream().filter(e -> {
				LocalDate d = TimeCalc.workDate(e, zone);
				return !d.isBefore(from) && !d.isAfter(to);
			}).toList();
			if (t.paidMinutes() == 0 && inRange.isEmpty()) {
				continue; // nobody to pay: skip people with no time at all
			}
			Timesheet sheet = sheets.get(m.userId());
			rows.add(new PayrollRow(m.userId(), m.name(), m.email(), m.role(), t.regularMinutes(), t.overtimeMinutes(),
					t.ptoMinutes(), t.breakMinutes(), t.paidMinutes(), inRange.size(), inRange.stream().anyMatch(TimeEntry::isOpen),
					isPeriod ? (sheet == null ? Timesheet.OPEN : sheet.getStatus()) : null));
			sum = sum.plus(t);
		}
		return new PayrollView(from, to, isPeriod, rows, sum);
	}

	@Transactional(readOnly = true)
	public String csv(UserPrincipal p, LocalDate from, LocalDate to) {
		PayrollView v = report(p, from, to);
		StringBuilder sb = new StringBuilder();
		sb.append("Employee,Email,Role,Period start,Period end,Regular hours,Overtime hours,PTO hours,Total paid hours,Break hours,Shifts,Timesheet status\n");
		for (PayrollRow r : v.rows()) {
			sb.append(cell(r.name())).append(',')
					.append(cell(r.email())).append(',')
					.append(cell(r.role())).append(',')
					.append(v.from()).append(',')
					.append(v.to()).append(',')
					.append(hours(r.regularMinutes())).append(',')
					.append(hours(r.overtimeMinutes())).append(',')
					.append(hours(r.ptoMinutes())).append(',')
					.append(hours(r.paidMinutes())).append(',')
					.append(hours(r.breakMinutes())).append(',')
					.append(r.shifts()).append(',')
					.append(cell(r.timesheetStatus() == null ? "" : r.timesheetStatus()))
					.append('\n');
		}
		return sb.toString();
	}

	static String hours(long minutes) {
		return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP).toPlainString();
	}

	/** Quotes a CSV cell and defuses spreadsheet formulas (=, +, -, @ at the start). */
	static String cell(String v) {
		if (v == null) {
			return "";
		}
		String t = v;
		if (!t.isEmpty() && "=+-@".indexOf(t.charAt(0)) >= 0) {
			t = "'" + t;
		}
		return "\"" + t.replace("\"", "\"\"") + "\"";
	}
}
