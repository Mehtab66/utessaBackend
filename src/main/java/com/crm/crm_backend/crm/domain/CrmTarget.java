package com.crm.crm_backend.crm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Quota for a team member, or for the whole practice when userId is null. */
@Getter
@Setter
@Entity
@Table(name = "crm_targets")
public class CrmTarget extends CrmAuditedEntity {

	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "assignee_name")
	private String assigneeName;

	@Column(nullable = false)
	private String metric;

	@Column(name = "target_value", nullable = false)
	private BigDecimal targetValue;

	@Column(name = "period_label")
	private String periodLabel;

	@Column(name = "period_start", nullable = false)
	private LocalDate periodStart;

	@Column(name = "period_end", nullable = false)
	private LocalDate periodEnd;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@Column(name = "created_by_name")
	private String createdByName;
}
