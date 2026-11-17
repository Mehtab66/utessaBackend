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

/**
 * One person record that moves through the CRM lifecycle (LEAD -> PATIENT).
 * Phone numbers keep a normalized 10-digit copy for caller lookup.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_patients")
public class Patient extends CrmAuditedEntity {

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "lifecycle_stage", nullable = false, columnDefinition = "crm_lifecycle")
	private Lifecycle lifecycleStage = Lifecycle.LEAD;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "lead_status", nullable = false, columnDefinition = "crm_lead_status")
	private LeadStatus leadStatus = LeadStatus.NEW;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_priority")
	private Priority priority = Priority.MEDIUM;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	@Column(name = "preferred_name")
	private String preferredName;

	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	private String gender;

	private String phone;

	@Column(name = "phone_normalized")
	private String phoneNormalized;

	@Column(name = "alt_phone")
	private String altPhone;

	@Column(name = "alt_phone_normalized")
	private String altPhoneNormalized;

	private String email;

	@Column(name = "preferred_contact")
	private String preferredContact;

	@Column(name = "preferred_language")
	private String preferredLanguage;

	@Column(name = "best_time_to_call")
	private String bestTimeToCall;

	@Column(name = "consent_call", nullable = false)
	private boolean consentCall = true;

	@Column(name = "consent_sms", nullable = false)
	private boolean consentSms;

	@Column(name = "consent_email", nullable = false)
	private boolean consentEmail;

	@Column(name = "do_not_contact", nullable = false)
	private boolean doNotContact;

	private String street;
	private String city;
	private String state;
	private String zip;

	private String source;

	@Column(name = "source_detail")
	private String sourceDetail;

	private String campaign;

	@Column(name = "referral_partner_id")
	private UUID referralPartnerId;

	@Column(name = "interested_service")
	private String interestedService;

	@Column(name = "preferred_provider")
	private String preferredProvider;

	@Column(name = "preferred_location")
	private String preferredLocation;

	@Column(name = "reason_for_visit", columnDefinition = "TEXT")
	private String reasonForVisit;

	@Column(name = "insurance_payer")
	private String insurancePayer;

	@Column(name = "insurance_plan")
	private String insurancePlan;

	@Column(name = "insurance_member_id")
	private String insuranceMemberId;

	@Column(name = "insurance_verified", nullable = false)
	private boolean insuranceVerified;

	@Column(name = "owner_user_id")
	private UUID ownerUserId;

	@Column(name = "owner_name")
	private String ownerName;

	@Column(name = "next_follow_up")
	private LocalDate nextFollowUp;

	@Column(name = "last_contact_at")
	private Instant lastContactAt;

	@Column(name = "call_outcome")
	private String callOutcome;

	@Column(name = "lost_reason")
	private String lostReason;

	private String mrn;

	@Column(name = "ehr_patient_id")
	private String ehrPatientId;

	@Column(name = "ehr_sync_status", nullable = false)
	private String ehrSyncStatus = "NOT_LINKED";

	@Column(name = "ehr_synced_at")
	private Instant ehrSyncedAt;

	@Column(name = "converted_at")
	private Instant convertedAt;

	private String tags;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@Column(name = "created_by_user_id")
	private UUID createdByUserId;

	/** INSURANCE or SELF_PAY. */
	@Column(name = "coverage_type", nullable = false)
	private String coverageType = "INSURANCE";

	@Column(name = "insurance_group")
	private String insuranceGroup;

	/** Relationship of the policy holder to the patient (Patient, Spouse, Parent, Other). */
	@Column(name = "policy_holder")
	private String policyHolder;

	@Column(name = "sms_reminders", nullable = false)
	private boolean smsReminders;

	public boolean isLead() {
		return lifecycleStage == Lifecycle.LEAD;
	}
}
