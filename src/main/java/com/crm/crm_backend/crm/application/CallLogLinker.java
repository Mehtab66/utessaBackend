package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.RcCallRepository;

/**
 * Keeps one call-history entry per real call. A call can reach the history twice: a staff
 * member documents it on the call desk, and the RingCentral sync imports the same call from the
 * call log. This finds the matching entry so the two are merged instead of duplicated.
 */
@Component
public class CallLogLinker {

	/** How far before the RingCentral start time a documented call may have been opened. */
	private static final long BEFORE_MINUTES = 10;
	/** How long after the call ended a staff member may still be documenting it. */
	private static final long AFTER_MINUTES = 90;

	private final ActivityRepository activities;
	private final RcCallRepository calls;

	public CallLogLinker(ActivityRepository activities, RcCallRepository calls) {
		this.activities = activities;
		this.calls = calls;
	}

	public static ActivityType typeFor(boolean inbound) {
		return inbound ? ActivityType.CALL_INBOUND : ActivityType.CALL_OUTBOUND;
	}

	/**
	 * Sync side: a call documented on the call desk for the same number and direction around the
	 * time of this RingCentral call, that no other RingCentral call has claimed yet.
	 */
	public Optional<Activity> findDocumented(UUID orgId, ActivityType type, String phoneNormalized,
			Instant startedAt, Integer durationSeconds) {
		if (phoneNormalized == null || startedAt == null) {
			return Optional.empty();
		}
		Instant from = startedAt.minus(BEFORE_MINUTES, ChronoUnit.MINUTES);
		Instant to = startedAt.plusSeconds(durationSeconds == null ? 0 : durationSeconds)
				.plus(AFTER_MINUTES, ChronoUnit.MINUTES);
		return activities.findByOrganizationIdAndActivityTypeAndOccurredAtBetween(orgId, type, from, to).stream()
				.filter(a -> !"Missed".equalsIgnoreCase(a.getCallOutcome()))
				.filter(a -> phoneNormalized.equals(CrmSupport.normalizePhone(a.getPhone())))
				.filter(a -> !calls.existsByOrganizationIdAndActivityId(orgId, a.getId()))
				.min(Comparator.comparing(a -> Math.abs(a.getOccurredAt().getEpochSecond() - startedAt.getEpochSecond())));
	}

	/**
	 * Call-desk side: an entry the RingCentral sync already created for this call (same number and
	 * direction, recent, not yet documented by a person), so documenting fills it in.
	 */
	public Optional<Activity> findSynced(UUID orgId, ActivityType type, String phone) {
		String normalized = CrmSupport.normalizePhone(phone);
		if (normalized == null) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		return activities.findByOrganizationIdAndActivityTypeAndOccurredAtBetween(orgId, type,
						now.minus(AFTER_MINUTES, ChronoUnit.MINUTES), now.plus(5, ChronoUnit.MINUTES)).stream()
				.filter(a -> RingCentralService.SYNC_LINE.equals(a.getPhoneLine()) && a.getBody() == null)
				.filter(a -> !"Missed".equalsIgnoreCase(a.getCallOutcome()))
				.filter(a -> normalized.equals(CrmSupport.normalizePhone(a.getPhone())))
				.filter(a -> calls.existsByOrganizationIdAndActivityId(orgId, a.getId()))
				.max(Comparator.comparing(Activity::getOccurredAt));
	}
}
