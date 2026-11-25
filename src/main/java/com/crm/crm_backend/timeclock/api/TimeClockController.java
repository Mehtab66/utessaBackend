package com.crm.crm_backend.timeclock.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.AuditView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.DecisionRequest;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.EntryRequest;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.EntryView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Member;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.NowRow;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PayrollView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PtoBody;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PtoDecision;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PtoView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PunchRequest;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.SettingsRequest;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.SettingsView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.StatusView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.SubmitRequest;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.TeamPeriodView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.TimesheetView;
import com.crm.crm_backend.timeclock.application.ClockService;
import com.crm.crm_backend.timeclock.application.EntryService;
import com.crm.crm_backend.timeclock.application.PayrollService;
import com.crm.crm_backend.timeclock.application.PtoService;
import com.crm.crm_backend.timeclock.application.TcSettingsService;
import com.crm.crm_backend.timeclock.application.TimesheetService;

/** Time Clock: punches, timesheets and approvals, time off, payroll and settings. Gated to the "tc" module. */
@RestController
@RequestMapping("/api/v1/timeclock")
public class TimeClockController {

	private final ClockService clock;
	private final EntryService entries;
	private final TimesheetService timesheets;
	private final PtoService pto;
	private final PayrollService payroll;
	private final TcSettingsService settings;

	public TimeClockController(ClockService clock, EntryService entries, TimesheetService timesheets, PtoService pto,
			PayrollService payroll, TcSettingsService settings) {
		this.clock = clock;
		this.entries = entries;
		this.timesheets = timesheets;
		this.pto = pto;
		this.payroll = payroll;
		this.settings = settings;
	}

	/* ── Punching ───────────────────────────────────────────────────── */

	@GetMapping("/status")
	public StatusView status(@AuthenticationPrincipal UserPrincipal p) {
		return clock.status(p);
	}

	@PostMapping("/clock-in")
	public StatusView clockIn(@AuthenticationPrincipal UserPrincipal p, @RequestBody(required = false) PunchRequest body) {
		return clock.clockIn(p, body == null ? null : body.note());
	}

	@PostMapping("/clock-out")
	public StatusView clockOut(@AuthenticationPrincipal UserPrincipal p, @RequestBody(required = false) PunchRequest body) {
		return clock.clockOut(p, body == null ? null : body.note());
	}

	@PostMapping("/break/start")
	public StatusView startBreak(@AuthenticationPrincipal UserPrincipal p) {
		return clock.startBreak(p);
	}

	@PostMapping("/break/end")
	public StatusView endBreak(@AuthenticationPrincipal UserPrincipal p) {
		return clock.endBreak(p);
	}

	@GetMapping("/team/now")
	public List<NowRow> teamNow(@AuthenticationPrincipal UserPrincipal p) {
		return clock.team(p);
	}

	/* ── Shifts ─────────────────────────────────────────────────────── */

	@PostMapping("/entries")
	@ResponseStatus(HttpStatus.CREATED)
	public EntryView createEntry(@AuthenticationPrincipal UserPrincipal p, @RequestBody EntryRequest body) {
		return entries.create(p, body);
	}

	@PutMapping("/entries/{id}")
	public EntryView updateEntry(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @RequestBody EntryRequest body) {
		return entries.update(p, id, body);
	}

	@DeleteMapping("/entries/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteEntry(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id,
			@RequestParam(required = false) String reason) {
		entries.delete(p, id, reason);
	}

	@GetMapping("/entries/{id}/history")
	public List<AuditView> history(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id) {
		return entries.history(p, id);
	}

	/* ── Timesheets ─────────────────────────────────────────────────── */

	@GetMapping("/timesheet")
	public TimesheetView timesheet(@AuthenticationPrincipal UserPrincipal p,
			@RequestParam(required = false) UUID userId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
		return timesheets.view(p, userId, periodStart);
	}

	@PostMapping("/timesheet/submit")
	public TimesheetView submit(@AuthenticationPrincipal UserPrincipal p, @RequestBody(required = false) SubmitRequest body) {
		return timesheets.submit(p, body == null ? null : body.periodStart(), body == null ? null : body.note());
	}

	@PostMapping("/timesheet/approve")
	public TimesheetView approve(@AuthenticationPrincipal UserPrincipal p, @RequestBody DecisionRequest body) {
		return timesheets.approve(p, body.userId(), body.periodStart(), body.note());
	}

	@PostMapping("/timesheet/reject")
	public TimesheetView reject(@AuthenticationPrincipal UserPrincipal p, @RequestBody DecisionRequest body) {
		return timesheets.reject(p, body.userId(), body.periodStart(), body.note());
	}

	@GetMapping("/team/timesheets")
	public TeamPeriodView teamTimesheets(@AuthenticationPrincipal UserPrincipal p,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
		return timesheets.team(p, periodStart);
	}

	/* ── Time off ───────────────────────────────────────────────────── */

	@GetMapping("/pto")
	public List<PtoView> pto(@AuthenticationPrincipal UserPrincipal p, @RequestParam(defaultValue = "mine") String scope) {
		return pto.list(p, scope);
	}

	@PostMapping("/pto")
	@ResponseStatus(HttpStatus.CREATED)
	public PtoView requestPto(@AuthenticationPrincipal UserPrincipal p, @RequestBody PtoBody body) {
		return pto.create(p, body);
	}

	@PostMapping("/pto/{id}/approve")
	public PtoView approvePto(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @RequestBody(required = false) PtoDecision body) {
		return pto.decide(p, id, true, body == null ? null : body.note());
	}

	@PostMapping("/pto/{id}/deny")
	public PtoView denyPto(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @RequestBody(required = false) PtoDecision body) {
		return pto.decide(p, id, false, body == null ? null : body.note());
	}

	@PostMapping("/pto/{id}/cancel")
	public PtoView cancelPto(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id) {
		return pto.cancel(p, id);
	}

	/* ── Payroll ────────────────────────────────────────────────────── */

	@GetMapping("/payroll")
	public PayrollView payroll(@AuthenticationPrincipal UserPrincipal p,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return payroll.report(p, from, to);
	}

	@GetMapping("/payroll.csv")
	public ResponseEntity<String> payrollCsv(@AuthenticationPrincipal UserPrincipal p,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		PayrollView v = payroll.report(p, from, to);
		String csv = payroll.csv(p, v.from(), v.to());
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payroll-" + v.from() + "-to-" + v.to() + ".csv\"")
				.contentType(new MediaType("text", "csv"))
				.body(csv);
	}

	/* ── Settings & people ──────────────────────────────────────────── */

	@GetMapping("/settings")
	public SettingsView settings(@AuthenticationPrincipal UserPrincipal p) {
		return settings.get(p);
	}

	@PutMapping("/settings")
	public SettingsView updateSettings(@AuthenticationPrincipal UserPrincipal p, @RequestBody SettingsRequest body) {
		return settings.update(p, body);
	}

	@GetMapping("/members")
	public List<Member> members(@AuthenticationPrincipal UserPrincipal p) {
		return settings.members(p);
	}
}
