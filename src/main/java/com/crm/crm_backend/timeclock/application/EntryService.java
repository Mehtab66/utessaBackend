package com.crm.crm_backend.timeclock.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.AuditView;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.EntryRequest;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.EntryView;
import com.crm.crm_backend.timeclock.domain.TcSettings;
import com.crm.crm_backend.timeclock.domain.TimeEntry;
import com.crm.crm_backend.timeclock.infrastructure.EntryAuditRepository;
import com.crm.crm_backend.timeclock.infrastructure.TimeEntryRepository;

/**
 * Adding and correcting shifts after the fact. Staff may fix their own time when the practice
 * allows it; managers may fix anyone's. Every change needs a reason and is kept in the audit trail.
 */
@Service
public class EntryService {

	private static final Duration MAX_SHIFT = Duration.ofHours(24);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d h:mm a");

	private final TimeEntryRepository entries;
	private final EntryAuditRepository audits;
	private final TcSupport support;
	private final EntryAuditor auditor;

	public EntryService(TimeEntryRepository entries, EntryAuditRepository audits, TcSupport support, EntryAuditor auditor) {
		this.entries = entries;
		this.audits = audits;
		this.support = support;
		this.auditor = auditor;
	}

	@Transactional
	public EntryView create(UserPrincipal p, EntryRequest req) {
		UUID org = support.org(p);
		UUID subject = req.userId() == null ? p.userId() : req.userId();
		boolean admin = authorize(p, subject, org);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		String reason = requireReason(req.reason());
		if (req.clockIn() == null || req.clockOut() == null) {
			throw ApiException.badRequest("Enter both the clock-in and clock-out time.");
		}
		Instant in = req.clockIn().truncatedTo(ChronoUnit.MINUTES);
		Instant out = req.clockOut().truncatedTo(ChronoUnit.MINUTES);
		int breaks = validate(in, out, req.breakMinutes());
		support.requireEditable(org, subject, in.atZone(zone).toLocalDate(), admin);
		requireNoOverlap(org, subject, in, out, null);

		TimeEntry e = new TimeEntry();
		e.setOrganizationId(org);
		e.setUserId(subject);
		e.setClockIn(in);
		e.setClockOut(out);
		e.setBreakMinutes(breaks);
		e.setNote(TcSupport.clean(req.note(), 1000));
		e.setSource(TimeEntry.MANUAL);
		e.setEdited(true);
		e.setCreatedBy(p.userId());
		e.setUpdatedBy(p.userId());
		entries.saveAndFlush(e);
		auditor.record(p, e, "CREATED", "Added shift " + span(in, out, zone) + breakText(breaks), reason);
		return TcSupport.view(e, support.name(subject), s, Instant.now(), false);
	}

	@Transactional
	public EntryView update(UserPrincipal p, UUID id, EntryRequest req) {
		UUID org = support.org(p);
		TimeEntry e = load(org, id);
		boolean admin = authorize(p, e.getUserId(), org);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		String reason = requireReason(req.reason());
		Instant in = req.clockIn() == null ? e.getClockIn() : req.clockIn().truncatedTo(ChronoUnit.MINUTES);
		Instant out;
		if (e.isOpen()) {
			out = req.clockOut() == null ? null : req.clockOut().truncatedTo(ChronoUnit.MINUTES);
		} else {
			out = req.clockOut() == null ? e.getClockOut() : req.clockOut().truncatedTo(ChronoUnit.MINUTES);
		}
		int breaks;
		if (out == null) {
			if (in.isAfter(Instant.now())) {
				throw ApiException.badRequest("The clock-in time can't be in the future.");
			}
			breaks = req.breakMinutes() == null ? e.getBreakMinutes() : Math.max(0, req.breakMinutes());
		} else {
			breaks = validate(in, out, req.breakMinutes() == null ? e.getBreakMinutes() : req.breakMinutes());
		}
		support.requireEditable(org, e.getUserId(), e.getClockIn().atZone(zone).toLocalDate(), admin);
		support.requireEditable(org, e.getUserId(), in.atZone(zone).toLocalDate(), admin);
		requireNoOverlap(org, e.getUserId(), in, out == null ? Instant.now() : out, e.getId());

		List<String> changes = new ArrayList<>();
		if (!in.equals(e.getClockIn())) {
			changes.add("clock-in " + TIME.format(e.getClockIn().atZone(zone)) + " → " + TIME.format(in.atZone(zone)));
		}
		if (!Objects.equals(out, e.getClockOut())) {
			changes.add("clock-out " + (e.getClockOut() == null ? "open" : TIME.format(e.getClockOut().atZone(zone)))
					+ " → " + (out == null ? "open" : TIME.format(out.atZone(zone))));
		}
		if (breaks != e.getBreakMinutes()) {
			changes.add("breaks " + e.getBreakMinutes() + " → " + breaks + " min");
		}
		String note = req.note() == null ? e.getNote() : TcSupport.clean(req.note(), 1000);
		if (!Objects.equals(note, e.getNote())) {
			changes.add("note updated");
		}
		if (changes.isEmpty()) {
			return TcSupport.view(e, support.name(e.getUserId()), s, Instant.now(), false);
		}
		e.setClockIn(in);
		e.setClockOut(out);
		if (out != null) {
			e.setBreakStartedAt(null);
		}
		e.setBreakMinutes(breaks);
		e.setNote(note);
		e.setEdited(true);
		e.setUpdatedBy(p.userId());
		try {
			entries.saveAndFlush(e);
		} catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("Those times clash with another shift.");
		}
		auditor.record(p, e, "UPDATED", "Changed " + String.join(", ", changes), reason);
		return TcSupport.view(e, support.name(e.getUserId()), s, Instant.now(), false);
	}

	@Transactional
	public void delete(UserPrincipal p, UUID id, String reasonText) {
		UUID org = support.org(p);
		TimeEntry e = load(org, id);
		boolean admin = authorize(p, e.getUserId(), org);
		TcSettings s = support.settings(org);
		ZoneId zone = TcSupport.zone(s);
		String reason = requireReason(reasonText);
		support.requireEditable(org, e.getUserId(), e.getClockIn().atZone(zone).toLocalDate(), admin);
		auditor.record(p, e, "DELETED", "Deleted shift " + span(e.getClockIn(), e.getClockOut(), zone), reason);
		entries.delete(e);
	}

	@Transactional(readOnly = true)
	public List<AuditView> history(UserPrincipal p, UUID id) {
		UUID org = support.org(p);
		support.require(p);
		List<AuditView> list = audits.findByOrganizationIdAndEntryIdOrderByCreatedAtAsc(org, id).stream()
				.map(a -> {
					if (!a.getSubjectUserId().equals(p.userId())) {
						support.requireAdmin(p);
					}
					return new AuditView(a.getAction(), a.getSummary(), a.getReason(), a.getActorName(), a.getCreatedAt());
				})
				.toList();
		if (list.isEmpty()) {
			throw ApiException.notFound("No history for that shift.");
		}
		return list;
	}

	/* ── Rules ──────────────────────────────────────────────────────── */

	/** Returns true when acting as a manager. */
	private boolean authorize(UserPrincipal p, UUID subject, UUID org) {
		support.require(p);
		if (!subject.equals(p.userId())) {
			support.requireAdmin(p);
			support.member(org, subject);
			return true;
		}
		if (support.isAdmin(p)) {
			return true;
		}
		if (!support.settings(org).isAllowManualEntries()) {
			throw ApiException.forbidden("Your practice has staff ask a manager to fix their time.");
		}
		return false;
	}

	private TimeEntry load(UUID org, UUID id) {
		return entries.findByIdAndOrganizationId(id, org).orElseThrow(() -> ApiException.notFound("Shift not found."));
	}

	private static String requireReason(String reason) {
		String r = TcSupport.clean(reason, 1000);
		if (r == null || r.length() < 3) {
			throw ApiException.badRequest("Add a short reason for the change (it's kept in the history).");
		}
		return r;
	}

	private static int validate(Instant in, Instant out, Integer breakMinutes) {
		if (!out.isAfter(in)) {
			throw ApiException.badRequest("Clock-out must be after clock-in.");
		}
		if (Duration.between(in, out).compareTo(MAX_SHIFT) > 0) {
			throw ApiException.badRequest("A shift can't be longer than 24 hours. Split it into two shifts.");
		}
		if (out.isAfter(Instant.now().plus(1, ChronoUnit.MINUTES))) {
			throw ApiException.badRequest("You can't add time in the future.");
		}
		int breaks = breakMinutes == null ? 0 : breakMinutes;
		if (breaks < 0 || breaks >= Duration.between(in, out).toMinutes()) {
			throw ApiException.badRequest("Breaks must be shorter than the shift.");
		}
		return breaks;
	}

	private void requireNoOverlap(UUID org, UUID user, Instant in, Instant out, UUID except) {
		boolean clash = entries.overlapping(org, user, in, out).stream().anyMatch(o -> !o.getId().equals(except));
		if (clash) {
			throw ApiException.conflict("Those times overlap another shift for the same person.");
		}
	}

	private static String span(Instant in, Instant out, ZoneId zone) {
		return TIME.format(in.atZone(zone)) + " – " + (out == null ? "open" : TIME.format(out.atZone(zone)));
	}

	private static String breakText(int breaks) {
		return breaks > 0 ? " (" + breaks + " min break)" : "";
	}

	static LocalDate day(Instant t, ZoneId zone) {
		return t.atZone(zone).toLocalDate();
	}
}
