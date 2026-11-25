package com.crm.crm_backend.timeclock.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Approval state of one person's pay period. No row means the period is still open. */
@Getter
@Setter
@Entity
@Table(name = "tc_timesheets")
public class Timesheet extends TcEntity {

	public static final String OPEN = "OPEN";
	public static final String SUBMITTED = "SUBMITTED";
	public static final String APPROVED = "APPROVED";
	public static final String REJECTED = "REJECTED";

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "period_start", nullable = false)
	private LocalDate periodStart;

	@Column(name = "period_end", nullable = false)
	private LocalDate periodEnd;

	@Column(nullable = false)
	private String status;

	@Column(name = "submitted_at")
	private Instant submittedAt;

	@Column(name = "decided_by")
	private UUID decidedBy;

	@Column(name = "decided_by_name")
	private String decidedByName;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(columnDefinition = "TEXT")
	private String note;
}
