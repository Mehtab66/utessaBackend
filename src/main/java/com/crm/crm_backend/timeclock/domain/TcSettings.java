package com.crm.crm_backend.timeclock.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A practice's pay period, overtime and rounding rules. */
@Getter
@Setter
@Entity
@Table(name = "tc_settings")
public class TcSettings {

	@Id
	@Column(name = "organization_id")
	private UUID organizationId;

	@Column(nullable = false)
	private String timezone = "America/New_York";

	@Column(name = "pay_period", nullable = false)
	private String payPeriod = "BIWEEKLY";

	@Column(name = "period_anchor", nullable = false)
	private LocalDate periodAnchor;

	@Column(name = "week_start", nullable = false)
	private String weekStart = "MONDAY";

	@Column(name = "daily_ot_hours")
	private BigDecimal dailyOtHours;

	@Column(name = "weekly_ot_hours")
	private BigDecimal weeklyOtHours = BigDecimal.valueOf(40);

	@Column(name = "rounding_minutes", nullable = false)
	private int roundingMinutes;

	@Column(name = "allow_manual_entries", nullable = false)
	private boolean allowManualEntries = true;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	@PreUpdate
	void touch() {
		updatedAt = Instant.now();
	}
}
