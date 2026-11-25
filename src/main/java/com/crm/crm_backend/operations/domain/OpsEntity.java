package com.crm.crm_backend.operations.domain;

import java.time.Instant;
import java.util.UUID;

import com.crm.crm_backend.shared.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

/** Tenant-scoped Practice Operations record with a UUID id and audit timestamps. */
@Getter
@Setter
@MappedSuperclass
public abstract class OpsEntity extends BaseTenantEntity {

	@Id
	private UUID id;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onOpsCreate() {
		assignTenant();
		if (id == null) {
			id = UUID.randomUUID();
		}
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onOpsUpdate() {
		updatedAt = Instant.now();
	}
}
