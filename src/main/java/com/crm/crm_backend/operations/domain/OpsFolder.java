package com.crm.crm_backend.operations.domain;

import java.time.Instant;
import java.util.UUID;

import com.crm.crm_backend.shared.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "ops_folders")
public class OpsFolder extends BaseTenantEntity {

	@Id
	private UUID id;

	/** VENDOR, RESOURCES or TRAINING. */
	@Column(nullable = false)
	private String space;

	@Column(name = "vendor_id")
	private UUID vendorId;

	@Column(name = "parent_id")
	private UUID parentId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private boolean locked;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		assignTenant();
		if (id == null) {
			id = UUID.randomUUID();
		}
		createdAt = Instant.now();
	}
}
