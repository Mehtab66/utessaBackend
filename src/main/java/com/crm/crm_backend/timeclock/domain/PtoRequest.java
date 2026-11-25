package com.crm.crm_backend.timeclock.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A time off request. */
@Getter
@Setter
@Entity
@Table(name = "tc_pto_requests")
public class PtoRequest extends TcEntity {

	public static final String PENDING = "PENDING";
	public static final String APPROVED = "APPROVED";
	public static final String DENIED = "DENIED";
	public static final String CANCELLED = "CANCELLED";

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "pto_type", nullable = false)
	private String ptoType;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Column(nullable = false)
	private BigDecimal hours;

	@Column(columnDefinition = "TEXT")
	private String reason;

	@Column(nullable = false)
	private String status = PENDING;

	@Column(name = "decided_by")
	private UUID decidedBy;

	@Column(name = "decided_by_name")
	private String decidedByName;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "decision_note", columnDefinition = "TEXT")
	private String decisionNote;
}
