package com.crm.crm_backend.operations.domain;

import java.time.Instant;
import java.time.LocalDate;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "patient_leads")
public class PatientLead extends BaseTenantEntity {

	@Id
	private UUID id;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	private String phone;
	private String email;
	private String source;

	@Column(name = "preferred_contact")
	private String preferredContact;

	@Column(name = "referral_source_detail")
	private String referralSourceDetail;

	@Column(name = "interested_service")
	private String interestedService;

	@Column(name = "preferred_provider")
	private String preferredProvider;

	@Column(name = "insurance_payer")
	private String insurancePayer;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "lead_status")
	private LeadStatus status = LeadStatus.NEW;

	@Column(name = "owner_name")
	private String ownerName;

	@Column(name = "next_follow_up")
	private LocalDate nextFollowUp;

	@Column(name = "last_contact_at")
	private Instant lastContactAt;

	@Column(name = "call_outcome")
	private String callOutcome;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		assignTenant();
		if (id == null) {
			id = UUID.randomUUID();
		}
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
		if (status == null) {
			status = LeadStatus.NEW;
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
