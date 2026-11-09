package com.crm.crm_backend.shared.tenant;

import java.util.UUID;

public final class TenantContext {

	private static final ThreadLocal<UUID> ORGANIZATION_ID = new ThreadLocal<>();
	private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
	private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(UUID organizationId, UUID userId, String role) {
		ORGANIZATION_ID.set(organizationId);
		USER_ID.set(userId);
		ROLE.set(role);
	}

	public static UUID getOrganizationId() {
		return ORGANIZATION_ID.get();
	}

	public static UUID getUserId() {
		return USER_ID.get();
	}

	public static String getRole() {
		return ROLE.get();
	}

	public static UUID requireOrganizationId() {
		UUID orgId = ORGANIZATION_ID.get();
		if (orgId == null) {
			throw new IllegalStateException("No organization in tenant context");
		}
		return orgId;
	}

	public static void clear() {
		ORGANIZATION_ID.remove();
		USER_ID.remove();
		ROLE.remove();
	}
}
