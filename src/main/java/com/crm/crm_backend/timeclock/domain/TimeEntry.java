package com.crm.crm_backend.timeclock.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One shift: clock in → clock out, with its breaks. Open while {@code clockOut} is null. */
@Getter
@Setter
@Entity
@Table(name = "tc_entries")
public class TimeEntry extends TcEntity {

	public static final String WEB = "WEB";
	public static final String MANUAL = "MANUAL";

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "clock_in", nullable = false)
	private Instant clockIn;

	@Column(name = "clock_out")
	private Instant clockOut;

	/** Finished breaks, in minutes. */
	@Column(name = "break_minutes", nullable = false)
	private int breakMinutes;

	/** Start of the break in progress, if any. */
	@Column(name = "break_started_at")
	private Instant breakStartedAt;

	@Column(columnDefinition = "TEXT")
	private String note;

	@Column(nullable = false)
	private String source = WEB;

	@Column(nullable = false)
	private boolean edited;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "updated_by")
	private UUID updatedBy;

	public boolean isOpen() {
		return clockOut == null;
	}

	public boolean isOnBreak() {
		return isOpen() && breakStartedAt != null;
	}

	/** Break minutes including a break still running at {@code now}. */
	public long breakMinutesAt(Instant now) {
		long running = breakStartedAt == null ? 0 : Math.max(0, Duration.between(breakStartedAt, now).toMinutes());
		return breakMinutes + running;
	}
}
