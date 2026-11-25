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
@Table(name = "ops_privileges")
public class Privilege extends OpsEntity {

	@Column(name = "staff_id", nullable = false)
	private UUID staffId;

	@Column(name = "facility_id", nullable = false)
	private UUID facilityId;

	private String category;
	private String department;

	@Column(columnDefinition = "TEXT")
	private String privileges;

	@Column(nullable = false)
	private String status;

	@Column(name = "applied_on")
	private LocalDate appliedOn;

	@Column(name = "approved_on")
	private LocalDate approvedOn;

	@Column(name = "reappoint_due")
	private LocalDate reappointDue;

	@Column(name = "fppe_status")
	private String fppeStatus;

	@Column(name = "fppe_due")
	private LocalDate fppeDue;

	@Column(name = "oppe_due")
	private LocalDate oppeDue;

	private String contact;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> history = new ArrayList<>();
}
