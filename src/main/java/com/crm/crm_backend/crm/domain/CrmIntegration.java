package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Phone-system or EHR connection settings for a practice. Credentials are never stored here. */
@Getter
@Setter
@Entity
@Table(name = "crm_integrations")
public class CrmIntegration extends CrmAuditedEntity {

	public static final String PHONE = "PHONE";
	public static final String EHR = "EHR";

	@Column(nullable = false)
	private String kind;

	private String provider;

	@Column(nullable = false)
	private String status = "NOT_CONNECTED";

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "settings_json", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> settings = new HashMap<>();

	@Column(name = "last_sync_at")
	private Instant lastSyncAt;
}
