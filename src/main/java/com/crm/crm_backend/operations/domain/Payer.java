package com.crm.crm_backend.operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "ops_payers")
public class Payer extends OpsEntity {

	@Column(nullable = false)
	private String name;

	@Column(name = "short_name", nullable = false)
	private String shortName;

	@Column(name = "payer_type")
	private String payerType;

	private String method;

	@Column(name = "typical_days")
	private Integer typicalDays;

	@Column(name = "recred_months")
	private Integer recredMonths;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;
}
