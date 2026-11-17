package com.crm.crm_backend.crm.domain;

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

/** A missed or cancelled visit on the recall call list, worked until rebooked or closed. */
@Getter
@Setter
@Entity
@Table(name = "crm_recalls")
public class Recall extends CrmAuditedEntity {

	public enum Status {
		TO_CALL,
		REBOOKED,
		NOT_REACHED,
		CLOSED
	}

	@Column(name = "patient_id", nullable = false)
	private UUID patientId;

	@Column(name = "appointment_id")
	private UUID appointmentId;

	@Column(name = "missed_at", nullable = false)
	private Instant missedAt;

	/** NO_SHOW or CANCELLED. */
	@Column(name = "miss_type", nullable = false)
	private String missType;

	@Column(name = "visit_type")
	private String visitType;

	@Column(name = "provider_name")
	private String providerName;

	/** UTESSA (marked here), EHR (pulled), UPLOAD (file import). */
	@Column(nullable = false)
	private String source = "UTESSA";

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_recall_status")
	private Status status = Status.TO_CALL;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_outcome")
	private String lastOutcome;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	@Column(name = "next_attempt_on")
	private LocalDate nextAttemptOn;

	@Column(name = "rebooked_appointment_id")
	private UUID rebookedAppointmentId;

	@Column(name = "close_reason")
	private String closeReason;

	@Column(columnDefinition = "TEXT")
	private String notes;
}
