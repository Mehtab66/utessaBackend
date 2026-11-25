package com.crm.crm_backend.operations.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A configurable tracker (CME, malpractice, onboarding and so on): field definitions plus rows per person. */
@Getter
@Setter
@Entity
@Table(name = "ops_tracker_tabs")
public class TrackerTab extends OpsEntity {

	private String code;

	/** PROVIDER or STAFF. */
	@Column(nullable = false)
	private String scope;

	@Column(nullable = false)
	private String name;

	@Column(name = "group_name")
	private String groupName;

	private String description;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> fields = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, Object> goal;

	@Column(nullable = false)
	private boolean standard;

	@Column(nullable = false)
	private boolean hidden;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;
}
