package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One text message in a conversation, threaded by normalized phone number. */
@Getter
@Setter
@Entity
@Table(name = "crm_messages")
public class PhoneMessage extends CrmAuditedEntity {

	@Column(name = "patient_id")
	private UUID patientId;

	@Column(nullable = false)
	private String phone;

	@Column(name = "phone_normalized", nullable = false)
	private String phoneNormalized;

	@Column(name = "contact_name")
	private String contactName;

	/** OUT or IN. */
	@Column(nullable = false)
	private String direction;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String body;

	/** QUEUED, SENT, DELIVERED, FAILED, RECEIVED. */
	@Column(nullable = false)
	private String status = "QUEUED";

	private String line;

	@Column(name = "sent_by_user_id")
	private UUID sentByUserId;

	@Column(name = "sent_by_name")
	private String sentByName;

	@Column(name = "read_at")
	private Instant readAt;
}
