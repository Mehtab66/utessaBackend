package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Referring physician, practice or facility that sends patients to the practice. */
@Getter
@Setter
@Entity
@Table(name = "crm_referral_partners")
public class ReferralPartner extends CrmAuditedEntity {

	@Column(nullable = false)
	private String name;

	@Column(name = "partner_type", nullable = false)
	private String partnerType = "PHYSICIAN";

	@Column(name = "relationship_status", nullable = false)
	private String relationshipStatus = "ACTIVE";

	private String npi;
	private String specialty;

	@Column(name = "contact_name")
	private String contactName;

	private String phone;
	private String fax;
	private String email;
	private String street;
	private String city;
	private String state;
	private String zip;

	@Column(name = "owner_user_id")
	private UUID ownerUserId;

	@Column(name = "owner_name")
	private String ownerName;

	@Column(name = "last_touch_at")
	private Instant lastTouchAt;

	@Column(columnDefinition = "TEXT")
	private String notes;
}
