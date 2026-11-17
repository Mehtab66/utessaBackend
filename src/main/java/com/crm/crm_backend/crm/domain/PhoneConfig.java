package com.crm.crm_backend.crm.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Practice call handling (userId null) or one user's phone preferences. */
@Getter
@Setter
@Entity
@Table(name = "crm_phone_config")
public class PhoneConfig extends CrmAuditedEntity {

	@Column(name = "user_id")
	private UUID userId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> config = new HashMap<>();
}
