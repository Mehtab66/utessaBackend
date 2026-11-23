package com.crm.crm_backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

/**
 * UUID id plus created/updated timestamps. Billing rows set {@code organization_id} explicitly
 * (not from the request's tenant context) because webhooks and background jobs have no session.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BillingAuditedEntity {

	@Id
	private UUID id;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreateBilling() {
		if (id == null) {
			id = UUID.randomUUID();
		}
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdateBilling() {
		updatedAt = Instant.now();
	}
}
