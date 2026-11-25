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

/** Vendor folders, required credentials and intake questions for one practice specialty. */
@Getter
@Setter
@Entity
@Table(name = "ops_specialty_templates")
public class SpecialtyTemplate extends OpsEntity {

	@Column(nullable = false)
	private String name;

	/** [{ name, parent }] where parent is the name of a top-level folder or null. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> folders = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<String> credentials = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<String> intake = new ArrayList<>();

	@Column(name = "system_template", nullable = false)
	private boolean systemTemplate;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;
}
