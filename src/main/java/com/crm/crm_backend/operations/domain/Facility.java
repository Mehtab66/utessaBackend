package com.crm.crm_backend.operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "ops_facilities")
public class Facility extends OpsEntity {

	@Column(nullable = false)
	private String name;

	@Column(name = "facility_type")
	private String facilityType;

	private String contact;

	@Column(name = "cycle_months")
	private Integer cycleMonths;
}
