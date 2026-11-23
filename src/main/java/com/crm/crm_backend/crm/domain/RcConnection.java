package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A staff member's RingCentral sign-in. Tokens are stored encrypted
 * ({@code *_enc}) and are never sent to the browser.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_rc_connections")
public class RcConnection extends CrmAuditedEntity {

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "access_token_enc", nullable = false, columnDefinition = "TEXT")
	private String accessTokenEnc;

	@Column(name = "refresh_token_enc", columnDefinition = "TEXT")
	private String refreshTokenEnc;

	@Column(name = "access_expires_at")
	private Instant accessExpiresAt;

	@Column(name = "refresh_expires_at")
	private Instant refreshExpiresAt;

	@Column(name = "rc_account_id")
	private String rcAccountId;

	@Column(name = "rc_extension_id")
	private String rcExtensionId;

	@Column(name = "extension_number")
	private String extensionNumber;

	@Column(name = "extension_name")
	private String extensionName;

	@Column(name = "caller_id_number")
	private String callerIdNumber;

	@Column(name = "sms_number")
	private String smsNumber;

	/** The extension's numbers: [{phoneNumber, usageType, features[]}]. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "phone_numbers_json", nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> phoneNumbers = new ArrayList<>();

	/** CONNECTED or ERROR (sign-in expired / revoked). */
	@Column(nullable = false)
	private String status = "CONNECTED";

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "last_sync_at")
	private Instant lastSyncAt;

	@Column(name = "last_message_sync_at")
	private Instant lastMessageSyncAt;
}
