package com.crm.crm_backend.timeclock.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the Time Clock API. Durations are whole minutes. */
public final class TcDtos {

	private TcDtos() {
	}

	/* ── People ─────────────────────────────────────────────────────── */

	public record Member(UUID userId, String name, String email, String role) {
	}

	/* ── Shifts ─────────────────────────────────────────────────────── */

	public record EntryView(
			UUID id,
			UUID userId,
			String userName,
			LocalDate workDate,
			Instant clockIn,
			Instant clockOut,
			long breakMinutes,
			Instant onBreakSince,
			long workedMinutes,
			String note,
			String source,
			boolean edited,
			boolean locked
	) {
	}

	public record PunchRequest(String note) {
	}

	/** Add or correct a shift. {@code userId} null = yourself. {@code reason} is kept in the audit trail. */
	public record EntryRequest(
			UUID userId,
			Instant clockIn,
			Instant clockOut,
			Integer breakMinutes,
			String note,
			String reason
	) {
	}

	public record AuditView(String action, String summary, String reason, String actorName, Instant at) {
	}

	/* ── My status ──────────────────────────────────────────────────── */

	public record StatusView(
			String state,               // OUT | IN | BREAK
			Instant since,
			EntryView current,
			long todayMinutes,
			long weekMinutes,
			long weekOvertimeMinutes,
			LocalDate periodStart,
			LocalDate periodEnd,
			long periodMinutes,
			String timesheetStatus,
			List<EntryView> today,
			String timezone,
			boolean manualEntriesAllowed
	) {
	}

	/* ── Timesheets ─────────────────────────────────────────────────── */

	public record Totals(
			long workedMinutes,
			long regularMinutes,
			long overtimeMinutes,
			long breakMinutes,
			long ptoMinutes,
			long paidMinutes
	) {
		public static final Totals ZERO = new Totals(0, 0, 0, 0, 0, 0);

		public Totals plus(Totals o) {
			return new Totals(workedMinutes + o.workedMinutes, regularMinutes + o.regularMinutes,
					overtimeMinutes + o.overtimeMinutes, breakMinutes + o.breakMinutes,
					ptoMinutes + o.ptoMinutes, paidMinutes + o.paidMinutes);
		}
	}

	public record DayView(
			LocalDate date,
			long workedMinutes,
			long regularMinutes,
			long overtimeMinutes,
			long breakMinutes,
			long ptoMinutes,
			List<String> timeOff,
			List<EntryView> entries
	) {
	}

	public record TimesheetView(
			UUID id,
			UUID userId,
			String userName,
			LocalDate periodStart,
			LocalDate periodEnd,
			String status,              // OPEN | SUBMITTED | APPROVED | REJECTED
			String note,
			Instant submittedAt,
			Instant decidedAt,
			String decidedByName,
			List<DayView> days,
			Totals totals,
			boolean hasOpenEntry,
			boolean canEdit,
			boolean canSubmit,
			boolean canDecide,
			boolean self,
			LocalDate previousStart,
			LocalDate nextStart
	) {
	}

	public record SubmitRequest(LocalDate periodStart, String note) {
	}

	public record DecisionRequest(UUID userId, LocalDate periodStart, String note) {
	}

	public record TeamPeriodRow(
			UUID userId,
			String name,
			String role,
			String status,
			Totals totals,
			boolean hasOpenEntry,
			int shifts,
			Instant submittedAt
	) {
	}

	public record TeamPeriodView(
			LocalDate periodStart,
			LocalDate periodEnd,
			LocalDate previousStart,
			LocalDate nextStart,
			List<TeamPeriodRow> rows
	) {
	}

	/* ── Who's working ──────────────────────────────────────────────── */

	public record NowRow(
			UUID userId,
			String name,
			String role,
			String state,               // OUT | IN | BREAK
			Instant since,
			long todayMinutes,
			long weekMinutes,
			boolean longShift,
			String timeOffToday
	) {
	}

	/* ── Time off ───────────────────────────────────────────────────── */

	public record PtoBody(String type, LocalDate startDate, LocalDate endDate, BigDecimal hours, String reason) {
	}

	public record PtoDecision(String note) {
	}

	public record PtoView(
			UUID id,
			UUID userId,
			String userName,
			String type,
			LocalDate startDate,
			LocalDate endDate,
			BigDecimal hours,
			String reason,
			String status,
			String decidedByName,
			Instant decidedAt,
			String decisionNote,
			Instant createdAt,
			boolean canCancel,
			boolean canDecide
	) {
	}

	/* ── Payroll ────────────────────────────────────────────────────── */

	public record PayrollRow(
			UUID userId,
			String name,
			String email,
			String role,
			long regularMinutes,
			long overtimeMinutes,
			long ptoMinutes,
			long breakMinutes,
			long paidMinutes,
			int shifts,
			boolean hasOpenEntry,
			String timesheetStatus
	) {
	}

	public record PayrollView(LocalDate from, LocalDate to, boolean payPeriod, List<PayrollRow> rows, Totals totals) {
	}

	/* ── Settings ───────────────────────────────────────────────────── */

	public record SettingsView(
			String timezone,
			String payPeriod,
			LocalDate periodAnchor,
			String weekStart,
			BigDecimal dailyOtHours,
			BigDecimal weeklyOtHours,
			int roundingMinutes,
			boolean allowManualEntries,
			boolean canEdit,
			boolean manager,
			LocalDate currentPeriodStart,
			LocalDate currentPeriodEnd
	) {
	}

	public record SettingsRequest(
			String timezone,
			String payPeriod,
			LocalDate periodAnchor,
			String weekStart,
			BigDecimal dailyOtHours,
			BigDecimal weeklyOtHours,
			Integer roundingMinutes,
			Boolean allowManualEntries
	) {
	}
}
