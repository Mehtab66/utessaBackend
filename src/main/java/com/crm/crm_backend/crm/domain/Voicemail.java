package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "crm_voicemails")
public class Voicemail extends CrmAuditedEntity {

	@Column(name = "patient_id")
	private UUID patientId;

	@Column(name = "from_phone", nullable = false)
	private String fromPhone;

	@Column(name = "phone_normalized")
	private String phoneNormalized;

	@Column(name = "caller_name")
	private String callerName;

	@Column(nullable = false)
	private String mailbox = "Main line";

	@Column(name = "duration_seconds")
	private Integer durationSeconds;

	@Column(columnDefinition = "TEXT")
	private String transcript;

	@Column(name = "recording_url")
	private String recordingUrl;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	/** NEW, HEARD, HANDLED. */
	@Column(nullable = false)
	private String status = "NEW";

	@Column(name = "handled_by_name")
	private String handledByName;

	@Column(name = "handled_at")
	private Instant handledAt;
}
