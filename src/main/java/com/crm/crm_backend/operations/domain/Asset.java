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

/** Equipment or a device. IT devices can carry a login whose password is stored encrypted. */
@Getter
@Setter
@Entity
@Table(name = "ops_assets")
public class Asset extends OpsEntity {

	private String tag;

	@Column(name = "asset_type", nullable = false)
	private String assetType;

	@Column(nullable = false)
	private String name;

	private String manufacturer;
	private String serial;
	private String location;
	private String room;

	@Column(name = "assigned_staff_id")
	private UUID assignedStaffId;

	@Column(nullable = false)
	private String status = "In use";

	@Column(name = "purchased_on")
	private LocalDate purchasedOn;

	private BigDecimal price;

	@Column(name = "warranty_ends")
	private LocalDate warrantyEnds;

	@Column(name = "pm_months")
	private Integer pmMonths;

	@Column(name = "last_pm_on")
	private LocalDate lastPmOn;

	@Column(name = "vendor_name")
	private String vendorName;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@Column(name = "it_host")
	private String itHost;

	@Column(name = "it_ip")
	private String itIp;

	@Column(name = "it_username")
	private String itUsername;

	@Column(name = "it_password_enc", columnDefinition = "TEXT")
	private String itPasswordEnc;

	@Column(name = "it_os")
	private String itOs;

	@Column(name = "it_rotated_on")
	private LocalDate itRotatedOn;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> service = new ArrayList<>();
}
