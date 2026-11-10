package com.crm.crm_backend.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.crm.crm_backend.shared.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "vendors")
public class Vendor extends BaseTenantEntity {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, length = 100)
	private String category;

	private String folder;

	@Column(name = "contact_name")
	private String contactName;

	@Column(name = "contact_role")
	private String contactRole;

	private String email;
	private String phone;
	private String website;

	@Column(name = "account_number")
	private String accountNumber;

	@Column(name = "handles_phi", nullable = false)
	private boolean handlesPhi;

	@Column(name = "contract_start")
	private LocalDate contractStart;

	@Column(name = "contract_end")
	private LocalDate contractEnd;

	@Column(name = "auto_renews", nullable = false)
	private boolean autoRenews;

	@Column(name = "cancellation_notice_days")
	private Integer cancellationNoticeDays;

	@Column(name = "monthly_cost")
	private BigDecimal monthlyCost;

	@Column(name = "payment_terms")
	private String paymentTerms;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "baa_status", nullable = false, columnDefinition = "vendor_baa_status")
	private VendorBaaStatus baaStatus = VendorBaaStatus.PENDING;

	@Column(name = "baa_signed_on")
	private LocalDate baaSignedOn;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@Column(nullable = false)
	private boolean active = true;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "documents_json", nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> documentsJson = new ArrayList<>();

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		assignTenant();
		if (id == null) {
			id = UUID.randomUUID();
		}
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
		if (baaStatus == null) {
			baaStatus = VendorBaaStatus.PENDING;
		}
		if (documentsJson == null) {
			documentsJson = new ArrayList<>();
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
