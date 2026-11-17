package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.crm.crm_backend.shared.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Immutable timeline entry: call, note, SMS, email, status change or system event. */
@Getter
@Setter
@Entity
@Table(name = "crm_activities")
public class Activity extends BaseTenantEntity {

	@Id
	private UUID id;

	@Column(name = "patient_id")
	private UUID patientId;

	@Column(name = "opportunity_id")
	private UUID opportunityId;

	@Column(name = "referral_id")
	private UUID referralId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "activity_type", nullable = false, columnDefinition = "crm_activity_type")
	private ActivityType activityType;

	@Column(nullable = false)
	private String subject;

	@Column(columnDefinition = "TEXT")
	private String body;

	private String phone;

	@Column(name = "call_outcome")
	private String callOutcome;

	@Column(name = "duration_seconds")
	private Integer durationSeconds;

	@Column(name = "call_reason")
	private String callReason;

	@Column(name = "caller_name")
	private String callerName;

	/** PATIENT, LEAD, NEW_CALLER, or a non-patient type (STAFF, REFERRING_OFFICE, PAYER, VENDOR, OTHER). */
	@Column(name = "caller_type")
	private String callerType;

	@Column(name = "phone_line")
	private String phoneLine;

	@Column(name = "ehr_sync_status", nullable = false)
	private String ehrSyncStatus = "NOT_LINKED";

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Column(name = "actor_name")
	private String actorName;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		assignTenant();
		if (id == null) {
			id = UUID.randomUUID();
		}
		createdAt = Instant.now();
		if (occurredAt == null) {
			occurredAt = createdAt;
		}
	}
}
