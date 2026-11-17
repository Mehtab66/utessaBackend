package com.crm.crm_backend.crm.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A care / service-line opportunity (new patient consult, treatment plan, program enrolment). */
@Getter
@Setter
@Entity
@Table(name = "crm_opportunities")
public class Opportunity extends CrmAuditedEntity {

	@Column(name = "patient_id", nullable = false)
	private UUID patientId;

	@Column(name = "referral_id")
	private UUID referralId;

	@Column(nullable = false)
	private String name;

	@Column(name = "service_line")
	private String serviceLine;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_opportunity_stage")
	private OpportunityStage stage = OpportunityStage.INQUIRY;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_priority")
	private Priority priority = Priority.MEDIUM;

	@Column(name = "estimated_value")
	private BigDecimal estimatedValue;

	private Integer probability;

	@Column(name = "expected_close_date")
	private LocalDate expectedCloseDate;

	@Column(name = "provider_name")
	private String providerName;

	@Column(name = "owner_user_id")
	private UUID ownerUserId;

	@Column(name = "owner_name")
	private String ownerName;

	@Column(name = "lost_reason")
	private String lostReason;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(columnDefinition = "TEXT")
	private String notes;

	public boolean isOpen() {
		return stage != OpportunityStage.WON && stage != OpportunityStage.LOST;
	}
}
