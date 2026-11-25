package com.crm.crm_backend.operations.domain;

import java.time.LocalDate;
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

@Getter
@Setter
@Entity
@Table(name = "ops_payer_enrollments")
public class PayerEnrollment extends OpsEntity {

	@Column(name = "staff_id", nullable = false)
	private UUID staffId;

	@Column(name = "payer_id", nullable = false)
	private UUID payerId;

	@Column(nullable = false)
	private String status;

	private String products;

	@Column(name = "enrollment_type")
	private String enrollmentType;

	@Column(name = "group_info")
	private String groupInfo;

	private String method;

	@Column(name = "submitted_on")
	private LocalDate submittedOn;

	private String reference;

	@Column(name = "provider_pin")
	private String providerPin;

	@Column(name = "effective_on")
	private LocalDate effectiveOn;

	@Column(name = "recred_due")
	private LocalDate recredDue;

	@Column(name = "follow_up_on")
	private LocalDate followUpOn;

	@Column(name = "owner_name")
	private String ownerName;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> history = new ArrayList<>();
}
