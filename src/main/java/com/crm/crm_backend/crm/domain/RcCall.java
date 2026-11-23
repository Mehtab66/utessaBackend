package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One RingCentral call-log record, mirrored into Utessa. */
@Getter
@Setter
@Entity
@Table(name = "crm_rc_calls")
public class RcCall extends CrmAuditedEntity {

	@Column(name = "rc_record_id", nullable = false)
	private String rcRecordId;

	@Column(name = "telephony_session_id")
	private String telephonySessionId;

	@Column(name = "session_id")
	private String sessionId;

	@Column(name = "connection_user_id")
	private UUID connectionUserId;

	/** INBOUND or OUTBOUND. */
	@Column(nullable = false)
	private String direction;

	private String result;

	@Column(nullable = false)
	private boolean missed;

	@Column(name = "from_number")
	private String fromNumber;

	@Column(name = "from_name")
	private String fromName;

	@Column(name = "to_number")
	private String toNumber;

	@Column(name = "to_name")
	private String toName;

	@Column(name = "phone_normalized")
	private String phoneNormalized;

	@Column(name = "patient_id")
	private UUID patientId;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "duration_seconds")
	private Integer durationSeconds;

	@Column(name = "has_recording", nullable = false)
	private boolean hasRecording;

	@Column(name = "activity_id")
	private UUID activityId;

	/** SENT, SKIPPED or FAILED — the missed-call auto-text. */
	@Column(name = "auto_text_status")
	private String autoTextStatus;

	@Column(name = "auto_text_note")
	private String autoTextNote;
}
