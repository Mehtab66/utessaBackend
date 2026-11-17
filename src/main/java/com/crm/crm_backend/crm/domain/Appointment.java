package com.crm.crm_backend.crm.domain;

import java.time.Instant;
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

@Getter
@Setter
@Entity
@Table(name = "crm_appointments")
public class Appointment extends CrmAuditedEntity {

	@Column(name = "patient_id", nullable = false)
	private UUID patientId;

	@Column(name = "opportunity_id")
	private UUID opportunityId;

	@Column(name = "referral_id")
	private UUID referralId;

	@Column(nullable = false)
	private String title;

	@Column(name = "appointment_type", nullable = false)
	private String appointmentType = "NEW_PATIENT";

	@Column(nullable = false)
	private String mode = "IN_PERSON";

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_appointment_status")
	private AppointmentStatus status = AppointmentStatus.SCHEDULED;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes = 30;

	@Column(name = "provider_name")
	private String providerName;

	private String location;

	@Column(name = "assigned_user_id")
	private UUID assignedUserId;

	@Column(name = "assigned_name")
	private String assignedName;

	@Column(name = "ehr_appointment_id")
	private String ehrAppointmentId;

	@Column(name = "ehr_sync_status", nullable = false)
	private String ehrSyncStatus = "NOT_LINKED";

	@Column(name = "cancel_reason")
	private String cancelReason;

	@Column(columnDefinition = "TEXT")
	private String notes;

	public boolean isActive() {
		return status == AppointmentStatus.SCHEDULED || status == AppointmentStatus.CONFIRMED
				|| status == AppointmentStatus.CHECKED_IN;
	}
}
