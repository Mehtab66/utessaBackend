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

@Getter
@Setter
@Entity
@Table(name = "crm_referrals")
public class Referral extends CrmAuditedEntity {

	@Column(name = "patient_id", nullable = false)
	private UUID patientId;

	@Column(name = "referral_partner_id")
	private UUID referralPartnerId;

	@Column(nullable = false)
	private String direction = "INBOUND";

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_referral_status")
	private ReferralStatus status = ReferralStatus.RECEIVED;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_priority")
	private Priority priority = Priority.MEDIUM;

	@Column(name = "referring_provider_name")
	private String referringProviderName;

	@Column(name = "referring_provider_npi")
	private String referringProviderNpi;

	@Column(name = "referred_to_provider")
	private String referredToProvider;

	@Column(name = "service_requested")
	private String serviceRequested;

	@Column(columnDefinition = "TEXT")
	private String reason;

	@Column(name = "diagnosis_codes")
	private String diagnosisCodes;

	@Column(name = "insurance_payer")
	private String insurancePayer;

	@Column(name = "authorization_number")
	private String authorizationNumber;

	@Column(name = "authorization_expires_on")
	private LocalDate authorizationExpiresOn;

	@Column(name = "received_on", nullable = false)
	private LocalDate receivedOn;

	@Column(name = "due_on")
	private LocalDate dueOn;

	@Column(name = "assigned_user_id")
	private UUID assignedUserId;

	@Column(name = "assigned_name")
	private String assignedName;

	@Column(name = "closed_reason")
	private String closedReason;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(columnDefinition = "TEXT")
	private String notes;

	public boolean isOpen() {
		return status != ReferralStatus.COMPLETED && status != ReferralStatus.DECLINED
				&& status != ReferralStatus.CANCELLED;
	}
}
