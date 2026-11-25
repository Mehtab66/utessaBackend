package com.crm.crm_backend.operations.domain;

import java.math.BigDecimal;
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
@Table(name = "ops_inventory_requests")
public class InventoryRequest extends OpsEntity {

	@Column(name = "item_id")
	private UUID itemId;

	@Column(nullable = false)
	private String name;

	private String sku;

	@Column(nullable = false)
	private int qty;

	private String unit;

	@Column(name = "requested_by")
	private UUID requestedBy;

	@Column(name = "requested_by_name")
	private String requestedByName;

	@Column(name = "needed_by")
	private LocalDate neededBy;

	@Column(nullable = false)
	private String priority = "Routine";

	@Column(columnDefinition = "TEXT")
	private String reason;

	@Column(nullable = false)
	private String status = "Requested";

	private BigDecimal estimate;

	@Column(name = "po_number")
	private String poNumber;

	@Column(name = "vendor_name")
	private String vendorName;

	private LocalDate eta;

	@Column(name = "unit_cost")
	private BigDecimal unitCost;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> log = new ArrayList<>();
}
