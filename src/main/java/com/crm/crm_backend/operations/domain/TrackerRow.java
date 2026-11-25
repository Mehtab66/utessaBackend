package com.crm.crm_backend.operations.domain;

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

@Getter
@Setter
@Entity
@Table(name = "ops_tracker_rows")
public class TrackerRow extends OpsEntity {

	@Column(name = "tab_id", nullable = false)
	private UUID tabId;

	@Column(name = "staff_id", nullable = false)
	private UUID staffId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> vals = new HashMap<>();
}
