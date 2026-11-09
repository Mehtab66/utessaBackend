package com.crm.crm_backend.shared.tenant;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@MappedSuperclass
public abstract class BaseTenantEntity {

	@Column(name = "organization_id", nullable = false, updatable = false)
	private UUID organizationId;

	@PrePersist
	protected void assignTenant() {
		if (organizationId == null) {
			organizationId = TenantContext.requireOrganizationId();
		}
	}
}
