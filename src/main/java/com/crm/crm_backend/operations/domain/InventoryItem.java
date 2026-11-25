package com.crm.crm_backend.operations.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "ops_inventory_items")
public class InventoryItem extends OpsEntity {

	@Column(nullable = false)
	private String name;

	private String sku;
	private String manufacturer;

	@Column(name = "vendor_name")
	private String vendorName;

	private String category;
	private String unit;
	private BigDecimal price;

	@Column(nullable = false)
	private int qty;

	@Column(name = "reorder_level")
	private Integer reorderLevel;

	@Column(name = "par_level")
	private Integer parLevel;

	private String location;
	private String lot;

	@Column(name = "expires_on")
	private LocalDate expiresOn;

	@Column(columnDefinition = "TEXT")
	private String notes;
}
