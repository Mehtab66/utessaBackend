package com.crm.crm_backend.shared.security;

import java.util.Map;
import java.util.Set;

import com.crm.crm_backend.shared.exception.ApiException;

public final class RoleGuard {

	private static final Map<String, Set<String>> PERMS = Map.ofEntries(
			Map.entry("OWNER", Set.of("dash", "creds", "staff", "vendors", "patients", "settings")),
			Map.entry("ADMIN", Set.of("dash", "creds", "staff", "vendors", "patients", "settings")),
			Map.entry("MANAGER", Set.of("dash", "creds", "staff", "vendors", "patients")),
			Map.entry("FRONT", Set.of("dash", "patients")),
			Map.entry("CLINICAL", Set.of("dash", "patients")),
			Map.entry("PROVIDER", Set.of("dash", "patients")),
			Map.entry("BILLING", Set.of("dash")),
			Map.entry("OUTREACH", Set.of("dash")),
			Map.entry("HR", Set.of("dash", "creds", "staff")),
			Map.entry("MEMBER", Set.of("dash"))
	);

	private RoleGuard() {
	}

	public static void requireOrgAccess(UserPrincipal principal, String permission) {
		if (principal == null) {
			throw ApiException.unauthorized("Not authenticated.");
		}
		if (principal.superAdmin()) {
			throw ApiException.forbidden("Platform super admins cannot access practice data without an impersonation session.");
		}
		if (principal.organizationId() == null) {
			throw ApiException.forbidden("No practice organization on this session.");
		}
		String role = principal.role() == null ? "MEMBER" : principal.role().toUpperCase();
		Set<String> allowed = PERMS.getOrDefault(role, Set.of("dash"));
		if (!allowed.contains(permission)) {
			throw ApiException.forbidden("Your role cannot access this area.");
		}
	}
}
